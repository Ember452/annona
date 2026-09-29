# HTTP 缓存头

## 强缓存与协商缓存

Cache-Control: max-age=3600 让浏览器在期限内直接使用本地副本，不发请求。
期限过后走协商：请求带 If-None-Match 携带上次的 ETag，资源未变化则返回 304 与空响应体。

## 日期格式与校验器

Expires 与 Last-Modified 用 RFC 1123 格式（GMT），不是 ISO-8601，解析响应头时不要混用两套解析器。
ETag 是资源指纹，强校验要求字节级一致，带 W/ 前缀的弱校验只要求语义一致。
