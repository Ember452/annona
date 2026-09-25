/**
 * annona 通用能力根包。
 *
 * <p>职责：与业务无关、与技术框架无关的通用能力——统一响应 {@code Result}、异常体系
 * {@code BusinessException} / {@code ErrorCode} / {@code GlobalExceptionHandler}、常量与
 * 注解与切面基类。
 *
 * <p>允许依赖：JDK、SLF4J、Jackson；{@code GlobalExceptionHandler} 单独引入 Spring Web MVC
 * 注解（{@code @RestControllerAdvice}）作为通用异常出口。
 *
 * <p>禁止：依赖 {@code io.annona.spi} 或 {@code io.annona.modules} 的任何类；引入业务概念。
 */
package io.annona.common;
