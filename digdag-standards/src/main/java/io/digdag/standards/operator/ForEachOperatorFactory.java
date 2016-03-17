package io.digdag.standards.operator;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.stream.Collectors;
import java.io.IOException;
import java.nio.file.Path;
import com.google.inject.Inject;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import io.digdag.spi.TaskRequest;
import io.digdag.spi.TaskResult;
import io.digdag.spi.TemplateEngine;
import io.digdag.spi.Operator;
import io.digdag.spi.OperatorFactory;
import org.immutables.value.Value;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.digdag.client.config.Config;
import io.digdag.client.config.ConfigFactory;
import io.digdag.client.config.ConfigException;
import static java.util.Locale.ENGLISH;

public class ForEachOperatorFactory
        implements OperatorFactory
{
    private static Logger logger = LoggerFactory.getLogger(ForEachOperatorFactory.class);

    @Inject
    public ForEachOperatorFactory()
    { }

    public String getType()
    {
        return "for_each";
    }

    @Override
    public Operator newTaskExecutor(Path archivePath, TaskRequest request)
    {
        return new ForEachOperator(archivePath, request);
    }

    private static class ForEachOperator
            extends BaseOperator
    {
        public ForEachOperator(Path archivePath, TaskRequest request)
        {
            super(archivePath, request);
        }

        @Override
        public TaskResult runTask()
        {
            Config params = request.getConfig();

            Config doConfig = request.getConfig().getNested("_do");

            Config map = params.getNested("_command");

            LinkedHashMap<String, List<JsonNode>> entries = new LinkedHashMap<>();
            for (String key : map.getKeys()) {
                entries.put(key, map.getList(key, JsonNode.class));
            }

            List<Config> combinations = buildCombinations(request.getConfig().getFactory(), entries);

            boolean parallel = params.get("_parallel", boolean.class, false);

            Config generated = doConfig.getFactory().create();
            for (Config combination : combinations) {
                Config subtask = params.getFactory().create();
                subtask.setAll(doConfig);
                subtask.getNestedOrSetEmpty("_export").setAll(combination);
                generated.set(
                        buildTaskName(combination),
                        subtask);
            }

            if (parallel) {
                generated.set("_parallel", parallel);
            }

            return TaskResult.defaultBuilder(request)
                .subtaskConfig(generated)
                .build();
        }

        private static List<Config> buildCombinations(ConfigFactory cf, Map<String, List<JsonNode>> entries)
        {
            List<Config> current = new ArrayList<>();
            for (Map.Entry<String, List<JsonNode>> pair : entries.entrySet()) {
                List<Config> next = new ArrayList<>();
                if (current.isEmpty()) {
                    for (JsonNode value : pair.getValue()) {
                        next.add(cf.create().set(pair.getKey(), value));
                    }
                }
                else {
                    for (Config seed : current) {
                        for (JsonNode value : pair.getValue()) {
                            next.add(seed.deepCopy().set(pair.getKey(), value));
                        }
                    }
                }
                current = next;
            }
            return current;
        }

        private static String buildTaskName(Config combination)
        {
            StringBuilder sb = new StringBuilder();
            sb.append("+for-");
            boolean first = true;
            for (String key : combination.getKeys()) {
                if (first) {
                    first = false;
                }
                else {
                    sb.append('&');
                }
                sb.append(key);
                sb.append('=');
                sb.append(combination.get(key, Object.class).toString());  // TODO percent encode
            }
            return sb.toString();
        }
    }
}
