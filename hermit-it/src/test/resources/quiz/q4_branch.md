# 按用户等级走不同的接口

站点：

- `GET /profile?user=<用户名>` 返回 `{"level":"vip"}` 或 `{"level":"normal"}`。
- `level` 是 `vip` 的用户，额度在 `GET /vip/quota`，返回 `{"quota":500}`。
- 其他用户的额度在 `GET /quota`，返回 `{"quota":100}`。
- 两个额度接口只能调其中一个。

写一个能力，入参 `user`（字符串，必填），输出 `quota`（整数）。

## 通用要求

- 只交一份完整的 `conch.yaml`，不要解释。
- `conch: quiz`，`driver: http`，`base_url: http://127.0.0.1:18080`，能力名固定为 `run`。
- 入参名和输出字段名按题面写，不要改名，不要多包一层。
- 除非题面写了要登录，否则 `credentials: {}`，不写 `auth`。
- 只能依据 Hermit 的规则说明（`hermit://conches/hermit_dev/guide`）来写。
