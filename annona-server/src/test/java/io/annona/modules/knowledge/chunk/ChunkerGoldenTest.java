package io.annona.modules.knowledge.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.annona.common.parse.DocumentBlock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * golden 快照测试（P1a-06 验收「golden 快照入库」；仓库首个快照先例，约定立于此）：
 * 快照 = 输入块 IR + 参数 + 期望分块全量（含偏移与文本），存 src/test/resources/golden/chunk/，
 * 结构化树相等比对。改算法必须显式重生成快照并在评审中逐字段确认 diff——
 * 快照的意义是把"行为变化"变成可见的评审对象，而不是让它们悄悄溜过单测断言。
 */
@DisplayName("Chunker golden 快照")
class ChunkerGoldenTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"chinese-outline", "sentence-window", "table-preamble"})
    @DisplayName("golden 输入的分块结果与快照逐字段一致")
    void matchesGolden(String name) throws Exception {
        JsonNode golden = mapper.readTree(getClass().getResourceAsStream("/golden/chunk/" + name + ".json"));

        JsonNode optionsNode = golden.get("options");
        ChunkOptions options = new ChunkOptions(
            optionsNode.get("sectionChunkSize").asInt(),
            optionsNode.get("overlap").asInt(),
            optionsNode.get("sentenceBreakRatio").asDouble());
        List<DocumentBlock> blocks = new ArrayList<>();
        for (JsonNode block : golden.get("blocks")) {
            blocks.add(new DocumentBlock(
                DocumentBlock.BlockType.valueOf(block.get("type").asText()),
                block.hasNonNull("level") ? block.get("level").asInt() : null,
                block.get("text").asText(),
                block.get("charStart").asInt(),
                block.get("charEnd").asInt()));
        }

        ArrayNode actual = mapper.createArrayNode();
        for (KnowledgeChunk chunk : Chunker.chunk(blocks, options)) {
            ObjectNode node = actual.addObject();
            node.put("index", chunk.index());
            node.put("headingPath", chunk.headingPath());
            node.put("charStart", chunk.charStart());
            node.put("charEnd", chunk.charEnd());
            node.put("text", chunk.text());
        }

        assertThat(actual).isEqualTo(golden.get("expected"));
    }
}
