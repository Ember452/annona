package io.annona.modules.planner.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 掌握度 golden 快照（沿用 QuestionPackGoldenTest 约定）：给定事件轨迹与参数，
 * {@link MasteryModel} 的 mastery/confidence/sampleSize 输出与
 * {@code src/test/resources/golden/planner/mastery-<case>.json} 逐字段比对。
 * 改公式或参数默认值会让 golden 红——行为变化必须显式重生快照并在评审确认 diff。
 *
 * <p>重生方式：{@code mvn test -Dgolden.update=true}（首跑/公式变更时刷新 expected）。
 */
@DisplayName("MasteryModel golden 快照")
class MasteryGoldenTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"trajectory", "coldstart", "all-wrong"})
    @DisplayName("golden 轨迹的掌握度输出与快照一致")
    void matchesGolden(String name) throws Exception {
        Path path = Path.of("src/test/resources/golden/planner", name + ".json");
        JsonNode golden = mapper.readTree(path.toFile());

        JsonNode p = golden.get("params");
        MasteryParams params = new MasteryParams(
            p.get("halfLifeDays").asDouble(), p.get("gainK").asDouble(),
            p.get("followUpWeight").asDouble(), p.get("lrBase").asDouble(),
            p.get("lrDecay").asDouble(), p.get("lrSampleCap").asInt(),
            p.get("confidenceDivisor").asInt(), p.get("neutralQualityWeight").asDouble());

        JsonNode t0node = golden.get("t0");
        Instant t0 = Instant.parse(t0node.asText());
        List<MasteryEvent> events = new ArrayList<>();
        for (JsonNode e : golden.get("events")) {
            events.add(new MasteryEvent(e.get("weightedScore").asDouble(),
                e.get("followUpDepth").asInt(),
                t0.plus(e.get("daysFromT0").asInt(), ChronoUnit.DAYS)));
        }
        JsonNode qwNode = golden.get("qualityWeight");
        Double qualityWeight = qwNode.isNull() ? null : qwNode.asDouble();
        Instant asOf = t0.plus(golden.get("asOfDayFromT0").asInt(), ChronoUnit.DAYS);

        MasteryView view = MasteryModel.evaluate(events, qualityWeight, params, asOf);

        ObjectNode actual = mapper.createObjectNode();
        actual.put("mastery", view.mastery());
        actual.put("confidence", view.confidence());
        actual.put("sampleSize", view.sampleSize());

        if (Boolean.getBoolean("golden.update")) {
            ((ObjectNode) golden).set("expected", actual);
            Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(golden));
        }
        JsonNode expected = golden.get("expected");
        assertThat(actual.get("mastery").asDouble())
            .isEqualTo(expected.get("mastery").asDouble());
        assertThat(actual.get("confidence").asDouble())
            .isEqualTo(expected.get("confidence").asDouble());
        assertThat(actual.get("sampleSize").asInt())
            .isEqualTo(expected.get("sampleSize").asInt());
        assertThat(new File(path.toString())).exists();
    }
}
