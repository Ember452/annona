/**
 * SKILL.md 注册表（P1b-01；skill-questionbank-adr §决策 1/2）。
 *
 * <p>职责：解析 {@code classpath:skills/<key>/SKILL.md}（front-matter 三必填 + persona 正文）
 * 与可选的 {@code skill.meta.yml}（展示元数据/考察维度/参考文件引用），启动期 fail-fast
 * （缺字段、key 重复、parent 悬空都拒绝起服），向
 * {@link io.annona.shared.direction.service.SkillDirectionCatalog} 供给内置方向清单
 * （由 shared/direction 的 BuiltinDirectionSeeder 播种），并解析 _shared 公共参考内容
 * （P1b-04 组卷注入用）。技能清单是 classpath 静态资源——新增技能 = 加目录，不改代码。
 *
 * <p>允许依赖：{@code io.annona.common}、{@code io.annona.shared.direction}（仅目录接口）。
 * 禁止：依赖其他模块、infrastructure。
 */
package io.annona.modules.interview.skill;
