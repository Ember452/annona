# OAuth2 授权码与 PKCE

## 授权码流程

客户端先跳转授权端点换取一次性 code，后端再用 code 加客户端凭证换 access_token。
access_token 有效期一般 2 小时；过期后用 grant_type=refresh_token 换新，不必让用户重新登录。

## PKCE

公共客户端无法安全保存密钥，改为随机生成 code_verifier，并把它的 SHA-256 结果作为 code_challenge 事先发给授权端点。
换 token 时带上原始 verifier，授权服务器比对哈希，防住授权码被截获后的冒用。
