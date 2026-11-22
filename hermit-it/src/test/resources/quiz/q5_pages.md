# 翻页直到没有下一页

站点：`GET /list?page=<页码>`，页码从 1 开始，返回

```html
<table>
<tr class="item"><td class="id">L11</td><td class="name">条目11</td></tr>
<tr class="item"><td class="id">L12</td><td class="name">条目12</td></tr>
</table>
<a class="next" href="/list?page=2">下一页</a>
```

最后一页没有 `a.next`。总页数不固定，不会超过 20 页。

写一个没有入参的能力，把所有页都读完，输出 `items`（数组），每项只有 `id`，顺序和站点一致。读完最后一页就停，不能多发请求。

## 通用要求

- 只交一份完整的 `conch.yaml`，不要解释。
- `conch: quiz`，`driver: http`，`base_url: http://127.0.0.1:18080`，能力名固定为 `run`。
- 入参名和输出字段名按题面写，不要改名，不要多包一层。
- 除非题面写了要登录，否则 `credentials: {}`，不写 `auth`。
- 只能依据 Hermit 的规则说明（`hermit://authoring/guide`）来写。
