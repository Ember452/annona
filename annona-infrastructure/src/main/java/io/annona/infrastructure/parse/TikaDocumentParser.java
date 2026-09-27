package io.annona.infrastructure.parse;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.parse.DocumentBlock;
import io.annona.common.parse.DocumentParser;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Tika 解析适配：AutoDetect 一次解析 PDF/DOCX/TXT/MD（knowledge-ingestion-adr §决策 1）。
 *
 * <p>借 🅖 DocumentParseService 的三个机制：① {@link NoOpEmbeddedExtractor} 忽略嵌入
 * 资源（附件/内嵌图，防递归解析与噪声）；② PDF 坐标排序（多栏版面按位置重排，否则
 * 双栏 PDF 抽出的文字是错序的）；③ 专用线程池 + 超时取消（损坏文件可能让解析死循环）。
 * 与 🅖 的关键差异：输出<b>结构化块 IR</b>（自定义 ContentHandler 把 XHTML 事件映射为
 * 标题/段落/列表/表格块并记录偏移），不是纯文本。
 *
 * <p>偏移口径：清洗后全文 = 各块清洗后文本按序拼接（块间分隔符不计入偏移，
 * 见 DocumentBlock 注释）；本类逐块清洗并累计偏移，保证 IR 自洽。
 */
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(TikaDocumentParser.class);

    /** 解析文本上限（借 🅖 MAX_TEXT_LENGTH）：防异常文件撑爆内存，超限报 TOO_LARGE。 */
    private static final int MAX_TEXT_CHARS = 5 * 1024 * 1024;

    private final ExecutorService executor;
    private final long timeoutMillis;

    public TikaDocumentParser(ExecutorService documentParseExecutor, DocumentParseProperties properties) {
        this.executor = documentParseExecutor;
        this.timeoutMillis = properties.getTimeout().toMillis();
    }

    @Override
    public List<DocumentBlock> parse(byte[] content, String filename) {
        try {
            return executor.submit(() -> doParse(content, filename))
                .get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            throw new BusinessException(ErrorCode.KB_DOC_PARSE_FAILED, "解析队列已满，请稍后重试");
        } catch (java.util.concurrent.TimeoutException e) {
            throw new BusinessException(ErrorCode.KB_DOC_PARSE_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.KB_DOC_PARSE_FAILED, "解析被中断");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof BusinessException businessException) {
                throw businessException;
            }
            log.warn("文档解析失败 filename={}", filename, cause);
            throw new BusinessException(ErrorCode.KB_DOC_PARSE_FAILED, "文档解析失败：" + rootMessage(cause));
        }
    }

    private List<DocumentBlock> doParse(byte[] content, String filename) {
        if (content.length == 0) {
            return List.of(); // 空文件合法（借 🅖 parseEmptyFile 返回空；Tika 对 0 字节流会抛异常）
        }
        // markdown 走自建通道：Tika 3.2.x 无 markdown 解析器，标题语义不能降级（MarkdownBlockParser 注释）
        if (MarkdownBlockParser.isMarkdown(filename)) {
            return MarkdownBlockParser.parse(new String(content, java.nio.charset.StandardCharsets.UTF_8));
        }
        AutoDetectParser parser = new AutoDetectParser();
        ParseContext context = new ParseContext();
        context.set(Parser.class, parser);
        context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedExtractor());
        // 多栏 PDF 按坐标排序、不抽内嵌图（借 🅖；缺省配置下双栏 PDF 文本会错序）
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false);
        pdfConfig.setSortByPosition(true);
        context.set(PDFParserConfig.class, pdfConfig);

        Metadata metadata = new Metadata();
        if (filename != null && !filename.isBlank()) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        }
        BlockContentHandler handler = new BlockContentHandler();
        try (InputStream input = new ByteArrayInputStream(content)) {
            parser.parse(input, handler, metadata, context);
        } catch (SAXException e) {
            // SAX 异常是 handler 主动中止（超文本上限）的通道
            if (e.getCause() instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(ErrorCode.KB_DOC_PARSE_FAILED, "文档结构解析失败");
        } catch (Exception e) {
            log.warn("文档解析失败 filename={}", filename, e);
            throw new BusinessException(ErrorCode.KB_DOC_PARSE_FAILED, "文档解析失败：" + rootMessage(e));
        }
        return handler.blocks();
    }

    private static String rootMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    /** 嵌入资源一律不解析（借 🅖 NoOpEmbeddedDocumentExtractor）：附件与内嵌图不进检索库。 */
    private static final class NoOpEmbeddedExtractor implements EmbeddedDocumentExtractor {

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return false;
        }

        @Override
        public void parseEmbedded(InputStream stream, org.xml.sax.ContentHandler handler,
            Metadata metadata, boolean outputHtml) {
            // 刻意空实现：shouldParseEmbedded=false 时 Tika 不会走到这里
        }
    }

    /**
     * SAX → 块 IR。Tika 输出 XHTML 事件：h1–h6 → HEADING、p → PARAGRAPH、li → LIST_ITEM、
     * pre → CODE、table → TABLE（td 拼为竖线分隔、tr 拼换行）。未知容器元素视为透明，
     * 文本落入当前块；无块打开时收到文本即开一个隐式 PARAGRAPH。
     */
    private static final class BlockContentHandler extends DefaultHandler {

        private final List<DocumentBlock> blocks = new ArrayList<>();
        private final StringBuilder committed = new StringBuilder();
        private final StringBuilder buffer = new StringBuilder();
        private DocumentBlock.BlockType type;
        private Integer level;
        private String openTag;

        List<DocumentBlock> blocks() {
            emit();
            return blocks;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            String name = elementName(localName, qName);
            if (name.isEmpty()) {
                return;
            }
            switch (name) {
                case "h1" -> open(DocumentBlock.BlockType.HEADING, 1, name);
                case "h2" -> open(DocumentBlock.BlockType.HEADING, 2, name);
                case "h3" -> open(DocumentBlock.BlockType.HEADING, 3, name);
                case "h4" -> open(DocumentBlock.BlockType.HEADING, 4, name);
                case "h5" -> open(DocumentBlock.BlockType.HEADING, 5, name);
                case "h6" -> open(DocumentBlock.BlockType.HEADING, 6, name);
                case "p" -> open(DocumentBlock.BlockType.PARAGRAPH, null, name);
                case "li" -> open(DocumentBlock.BlockType.LIST_ITEM, null, name);
                case "pre" -> open(DocumentBlock.BlockType.CODE, null, name);
                case "table" -> open(DocumentBlock.BlockType.TABLE, null, name);
                case "td", "th" -> {
                    ensureOpen(DocumentBlock.BlockType.TABLE);
                    if (buffer.length() > 0 && !endsWith(buffer, "\n")) {
                        buffer.append("|");
                    }
                }
                case "tr" -> {
                    ensureOpen(DocumentBlock.BlockType.TABLE);
                    buffer.append("\n");
                }
                default -> {
                    // 透明容器：文本继续落入当前块
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (type == null) {
                open(DocumentBlock.BlockType.PARAGRAPH, null, "#implicit");
            }
            buffer.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String name = elementName(localName, qName);
            if (openTag != null && openTag.equals(name)) {
                emit();
            }
        }

        private void open(DocumentBlock.BlockType blockType, Integer headingLevel, String tag) {
            if (type != null) {
                emit();
            }
            type = blockType;
            level = headingLevel;
            openTag = tag;
        }

        /** 无显式块打开时的兜底（表格内文本、根级裸文本）。 */
        private void ensureOpen(DocumentBlock.BlockType blockType) {
            if (type == null) {
                type = blockType;
                level = null;
                openTag = "#implicit";
            }
        }

        private void emit() {
            if (type == null) {
                return;
            }
            String cleaned = TextCleaner.clean(buffer.toString());
            buffer.setLength(0);
            if (!cleaned.isBlank() && committed.length() + cleaned.length() <= MAX_TEXT_CHARS) {
                blocks.add(new DocumentBlock(type, level, cleaned, committed.length(),
                    committed.length() + cleaned.length()));
                committed.append(cleaned);
            }
            type = null;
            level = null;
            openTag = null;
        }

        private static boolean endsWith(StringBuilder builder, String suffix) {
            int start = builder.length() - suffix.length();
            return start >= 0 && builder.substring(start).equals(suffix);
        }

        private static String elementName(String localName, String qName) {
            return localName == null || localName.isEmpty() ? (qName == null ? "" : qName) : localName;
        }
    }
}
