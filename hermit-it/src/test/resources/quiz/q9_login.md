# 登录后有时要先同意协议

这道题要登录。凭证：`username`（文本）和 `password`（秘密）。

站点：

- `POST /login`，表单字段 `username`、`password`（原文，不用加密）。成功后种下 Cookie `SID`，返回一个 HTML 页面。
- 账号第一次登录时，这个页面的正文里有「请先同意用户协议」。这时必须再发一次 `POST /agree`（没有参数），否则后面的接口返回 403。已经同意过的账号，页面里没有这句话，不能发 `/agree`。
- `GET /me` 返回 `{"name":"张三"}`，没登录时返回 401。

写一个没有入参的能力，输出 `name`（字符串）。同意协议属于登录的一部分，写在登录步骤里。

## 通用要求

- 只交一份完整的 `conch.yaml`，不要解释。
- `conch: quiz`，`driver: http`，`base_url: http://127.0.0.1:18080`，能力名固定为 `run`。
- 入参名和输出字段名按题面写，不要改名，不要多包一层。
- 除非题面写了要登录，否则 `credentials: {}`，不写 `auth`。
- 只能依据 Hermit 的规则说明（`hermit://authoring/guide`）来写。
