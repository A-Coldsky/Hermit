# 有时要多点一次确认

站点：

- `POST /order/submit`，表单字段 `id`。多数时候直接返回 `{"order_no":"SO-…"}`。
- 金额较大的单子，这一步返回的是一个 HTML 页面，正文里有「请再次确认」，没有单号。这时要再发 `POST /order/confirm`，表单字段同样是 `id`，它返回 `{"order_no":"SO-…"}`。
- 不需要确认的单子不能发 `/order/confirm`。

写一个能力，入参 `id`（字符串，必填），输出 `order_no`（字符串）：最后拿到的单号。

## 通用要求

- 只交一份完整的 `conch.yaml`，不要解释。
- `conch: quiz`，`driver: http`，`base_url: http://127.0.0.1:18080`，能力名固定为 `run`。
- 入参名和输出字段名按题面写，不要改名，不要多包一层。
- 除非题面写了要登录，否则 `credentials: {}`，不写 `auth`。
- 只能依据 Hermit 的规则说明（`hermit://conches/hermit_dev/guide`）来写。
