package io.annona.modules.interview.orchestrator.pack;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.annona.modules.interview.orchestrator.plan.InterviewPlan;
import io.annona.shared.question.QuestionCandidate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 组卷 golden 快照（沿用 ChunkerGoldenTest 的仓库约定）：算法输出与
 * {@code src/test/resources/golden/pack/*.json} 逐字段树比对。改 {@link PackRules} 阈值或抽取
 * 顺序必须显式重生成快照并在评审中确认 diff——行为变化要可见，不是悄悄过断言。
 */
@DisplayName("QuestionPackService golden 快照")
class QuestionPackGoldenTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"basic"})
    @DisplayName("golden 输入的组卷结果与快照逐字段一致")
    void matchesGolden(String name) throws Exception {
        JsonNode golden = mapper.readTree(
            getClass().getResourceAsStream("/golden/pack/" + name + ".json"));

        JsonNode planNode = golden.get("plan");
        List<Integer> difficulties = new ArrayList<>();
        planNode.get("difficulties").forEach(n -> difficulties.add(n.asInt()));
        InterviewPlan plan = new InterviewPlan(
            planNode.get("totalCount").asInt(), difficulties,
            planNode.get("followUpDepth").asInt());

        List<QuestionCandidate> pool = new ArrayList<>();
        for (JsonNode q : golden.get("pool")) {
            pool.add(new QuestionCandidate(idOf(q.get("seq").asInt()),
                "题-" + q.get("seq").asInt(), q.get("difficulty").asInt(), 0));
        }

        Map<UUID, StemSimilarity> hits = new LinkedHashMap<>();
        for (JsonNode h : golden.get("dedupHits")) {
            UUID id = idOf(h.get("seq").asInt());
            hits.put(id, new StemSimilarity(id, h.get("cosine").asDouble(),
                h.get("keywordHit").asBoolean()));
        }

        PackResult result = new QuestionPackService().pack(plan, pool, hits);

        ArrayNode actual = mapper.createArrayNode();
        result.questionIds().forEach(id -> actual.add(id.toString()));
        result.skippedReasons().forEach(actual::add);
        ArrayNode expected = mapper.createArrayNode();
        golden.get("expected").get("questionIds").forEach(n -> expected.add(n.asText()));
        golden.get("expected").get("skippedReasons").forEach(n -> expected.add(n.asText()));

        assertThat(actual).isEqualTo(expected);
    }

    private static UUID idOf(int seq) {
        return UUID.nameUUIDFromBytes(("q" + seq).getBytes());
    }
}
