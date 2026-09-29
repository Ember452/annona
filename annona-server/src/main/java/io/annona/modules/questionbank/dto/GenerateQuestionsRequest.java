package io.annona.modules.questionbank.dto;

/**
 * 出题请求。范围校验在服务层做（BAD_REQUEST）：difficulty 1–5（决策层数值口径）、
 * questionCount 1–30（借 🅖 上限）、followUpCount 0–5。
 */
public record GenerateQuestionsRequest(Integer difficulty, Integer questionCount,
                                       Integer followUpCount) {
}
