# 本地 HTTP API（验证用输入通道）

给「不模拟点击也能打字验证」加的内置通道：输入法进程内起一个只绑回环地址的 HTTP 服务，
用 `adb forward` 映射到电脑上，curl / fetch 就能发按键、读上屏文本与候选列表。
实现：`app/src/main/java/com/osfans/trime/api/LocalApiServer.kt`。

## 开关与连接

服务随输入法视图创建时启动（`InputView` 构造时调 `LocalApiServer.start()`），
**键盘没弹出来时不会有监听**，所以先点一下输入框。端口固定 `127.0.0.1:25344`（`API_VERSION = 1`）。

```powershell
adb forward tcp:25344 tcp:25344
curl.exe -s http://127.0.0.1:25344/ping
# {"ok":true,"api":1}
```

- 只绑 `127.0.0.1`，不监听外网；无鉴权，仅限本机 adb 调试场景，别在正式包里暴露到局域网。
- 端口被占用或 bind 失败时只打一条 `Timber.e`，不影响输入法本体。
- 请求上限：头部 16 KiB、body 64 KiB；单请求 Rime 等待 5 秒、主线程等待 2 秒。

## 接口

### GET /ping
`{"ok":true,"api":1}` —— 只证明服务在跑。

### GET /state
当前输入法状态（读的是 Rime 实时状态，不是缓存）：

```json
{"ok":true,"ready":true,"schemaId":"mydomain","asciiMode":false,
 "composing":false,"rawInput":"","candidates":[{"text":"你好","comment":""}],
 "keyboardId":"my_pinyin","symbolsReturnId":"","lastCommit":"你好"}
```

| 字段 | 含义 |
| --- | --- |
| `ready` | Rime 是否已就绪；false 时其余字段是占位值 |
| `schemaId` / `asciiMode` | 当前方案 id / 是否 ASCII 模式 |
| `composing` / `rawInput` | 是否在组词 / 当前原始输入 |
| `candidates` | 候选（最多 10 条，带 `comment`） |
| `keyboardId` | 当前键盘布局 id（`my_pinyin`、`symbols_en`…） |
| `symbolsReturnId` | 符号页记录的回退键盘 id（空串表示还没记录） |
| `lastCommit` | 本进程内最近一次上屏的文本 |

### POST /type
按字符串逐字符走 `simulateKeySequence`（等价于真实按键），回 `{ok, state}`。

```powershell
curl.exe -s -X POST http://127.0.0.1:25344/type -H "Content-Type: application/json" -d '{"text":"nihao"}'
```

### POST /press
按**键帽标签**点一个键（不是键码），回 `{ok, keyboardId, lastCommit}`；
标签在当前键盘找不到时回 `{"ok":false,"error":"key not found"}`。

```powershell
curl.exe -s -X POST http://127.0.0.1:25344/press -H "Content-Type: application/json" -d '{"label":"?"}'
```

### POST /keyboard
切键盘布局，`{"id":"symbols_en"}`，回 `{ok}`。

### POST /candidate
选候选，`{"index":0}`，回 `{ok, lastCommit}`。

### POST /clear
清空当前组词，回 `{ok}`。

## 语义细节（踩过的坑）

- `lastCommit` 只在主线程读写；每次动作前后会短轮询 5×40 ms（`settleLastCommit`），
  避免读到动作**之前**的值。要判断「这次到底上屏了什么」，用响应里的 `lastCommit`。
- `/press` 先投递按键任务，再投递一个空 Rime 任务，空任务返回即代表 Rime 队列已排空，
  所以读到的是处理完之后的状态。
- `/type` 走的是真实按键路径，所以会受 `ascii_mode`、`ascii_punct` 影响——
  中文符号要在 `asciiMode=false` 时打。
- 键盘 id 由 `KeyboardWindow` 提供；符号页在 `SYMBOLS_PAGE_ORDER` 里
  （`symbols`、`symbols_cn`、`symbols_en`、`symbols_net`、`symbols_math`、`symbols_arrow`、`symbols_num`）。

## 用它做回归的配方

```powershell
adb forward tcp:25344 tcp:25344
# 1) 造一个联想伪组合段：打字 → 选首候选
curl.exe -s -X POST http://127.0.0.1:25344/type -H "Content-Type: application/json" -d '{"text":"nihao"}'
curl.exe -s -X POST http://127.0.0.1:25344/candidate -H "Content-Type: application/json" -d '{"index":0}'
# 2) 切符号页：lastCommit 必须仍是「你好」，不能被「请问/我是」污染
curl.exe -s -X POST http://127.0.0.1:25344/keyboard -H "Content-Type: application/json" -d '{"id":"symbols_en"}'
# 3) 符号页按键：必须上屏且自动回拼音键盘
curl.exe -s -X POST http://127.0.0.1:25344/press -H "Content-Type: application/json" -d '{"label":"?"}'
```

注意 PowerShell 里 JSON body 带逗号容易被拆坏，稳妥做法是先把 body 写进文件
（`[System.IO.File]::WriteAllText($p, $json, [Text.UTF8Encoding]::new($false))`）再用
`curl.exe --data-binary "@$p"`。
