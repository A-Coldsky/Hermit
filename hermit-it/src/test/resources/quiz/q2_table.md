# 读一张 HTML 表格

站点：`GET /notices` 返回

```html
<table id="notices"><tr><th>编号</th><th>标题</th></tr>
<tr class="row"><td>N1</td><td>放假通知</td></tr>
<tr class="row"><td>N2</td><td>系统升级</td></tr></table>
```

行数不固定。写一个没有入参的能力，输出 `items`（数组），每项有 `id`（编号）和 `title`（标题），不含表头。

## 通用要求

- 只交一份完整的 `conch.yaml`，不要解释。
- `conch: quiz`，`driver: http`，`base_url: http://127.0.0.1:18080`，能力名固定为 `run`。
- 入参名和输出字段名按题面写，不要改名，不要多包一层。
- 除非题面写了要登录，否则 `credentials: {}`，不写 `auth`。
- 只能依据 Hermit 的规则说明（`hermit://conches/hermit_dev/guide`）来写。
