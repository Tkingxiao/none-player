# AGENTS.md — None Player 工程代理操作手册

> 面向在本仓库工作的 AI 代理（也适用于人类协作者）。所有条目来自真实踩坑记录
> （案例编号 Exxx 对应 `docs/完成文档.md`；无编号的来自 2026-10-25 会话实录；
> 标注 E231 实录的来自 2026-10-26 会话）。
> 表述为公式化模板：**症状 → 病因 → 排查 → 修复 → 预防**。

---

## 0. 铁律（违反即返工）

| # | 铁律 | 依据 |
|---|------|------|
| R1 | 门禁命令**禁止** `| head`（SIGPIPE 顶替退出码 → 假绿） | 完成文档 §6 |
| R2 | 任何长任务（构建/测试/拉取）**>5 分钟无进展必须判活**，禁止盲等（§3 判活协议） | 2026-10-25 门禁三连挂死实录 |
| R3 | `screenshots/` 与 `docs/screenshots` 在 .gitignore 里**有意排除**——证据图本地留存，证据 `.txt` 入库；`git add` 被拦时**不许 `-f`** | .gitignore L50-53 |
| R4 | 文档三件套流转：`任务文档.md`（未完成）→ `审查文档.md`（待审）→ `完成文档.md`（审过）；完成即从上游删除，不堆积 | 任务文档头部规则 |
| R5 | 插件间禁止互相依赖（`plugin-* → plugin-*` 不允许）；跨插件共用的小函数允许各自持有一份 | settings.gradle.kts / E230 |
| R6 | 注释与 UI 文案用中文；注释写"为什么/约束"，不写"这行做什么"；改动处标注批次号（Exxx） | 全仓库惯例 |
| R7 | 中文字面量不资源化（D5 裁定）；不预先做 RTL/多语言承诺（D6） | E197 |
| R8 | 任务描述与代码现状矛盾时（如"移除 X 二次确认"而 X 根本不存在），先做**条目考古**（`git log -S`）再定方向，如实按"经查已实现/条目陈旧"收口，不硬造 | E217/E230 |

---

## 1. 标准工作流公式

```
挑条目（任务文档） 
  → 实现（代码 + 单测；纯函数缝优先，执行侧依赖 Android 运行态的走设备取证）
  → 门禁（§2 全量四件，全绿才算过）
  → 设备取证（emulator/真机；读数落 docs/evidence/eXXX-YYYY-MM-DD-device.txt）
  → 审查（自查 diff：逐文件重读、语义边界、陈旧注释）
  → 文档流转（完成文档新条目 + 任务文档删/改条目 + 门禁基线 §6 更新）
  → 提交（§5 规范，一批一 commit）
```

**门禁四件**（在 `None Player/` 目录下）：

```
./gradlew test assembleDebug assembleRelease lint
```

收数公式：测试数（扫 `**/build/test-results/**/TEST-*.xml` 的 tests/failures/errors/skipped）+ lint issue 数（`**/build/reports/lint-results-*.xml` 的 `<issue ` 计数）+ APK 字节数。写进完成文档。

**release 产物落点（E231 起）**：`assembleRelease` 收尾自动把 `*.apk` 原样拷到
`None Player/app/release/`（覆盖写，始终最新一轮；.gitignore 已排除）——装机/
取证/交付一律取这里，不再摸 `build/outputs/apk/release/` 深层路径。

---

## 2. 门禁与超时纪律

### 2.1 五分钟判活协议（核心）

**适用**：gradle 任何任务、adb 拉取、批处理脚本——一切后台长任务。

```
IF 任务运行 > 5min 且进展指标不变（日志行数 / 结果文件数 / 输出字节）
THEN
  ① CPU 采样判活（间隔 10s 采两次）：
       powershell "Get-Process java | Select Id,CPU"
       ΔCPU(10s) < 0.5s 且无 Test worker 进程 → 判死，进入 ②
  ② jstack 找等待点（谁是 gradle daemon / 谁是测试 worker）：
       jstack <pid> | grep -m1 "GradleWorkerMain|daemon"     # 认人
       jstack <worker> | grep -A25 '"Test worker"'           # 测试挂在哪
       jstack <worker> | grep -B3 -A25 'noneplayer'          # 应用帧在等什么
  ③ 处置（按序）：
       TaskStop 后台任务 → ./gradlew --stop → 修正 → 分阶段重跑
       （先隔离可疑模块：./gradlew :模块:testDebugUnitTest，再全量）
  ④ 复盘归档：真因 + 证据进 docs/evidence/，必要时重开任务文档观察项
FI
禁止：超过 10 分钟不做任何判活检查的盲等。
```

**进展指标三选一**（管道缓冲会骗人，见 E-B2）：
- 输出文件行数 `wc -l`（前提：重定向到文件，不套 tail）
- 测试结果文件数 `ls build/test-results/testDebugUnitTest | wc -l`
- `grep -c "^> Task"` 任务行计数

### 2.2 已知挂死源（见到直接按方案处置）

| 症状 | 病因 | 处置 |
|------|------|------|
| `ScanWalkParallelismBenchTest` 卡 `runBlocking`，jstack 见 Test worker 等 `Sandbox.runOnMainThread`、SDK Main 等 `joinBlocking`、全部 DefaultDispatcher worker 空闲且无应用帧 | Robolectric 主线程模型与协程事件循环交互，续体丢失于停泊线程的 ThreadLocalEventLoop（E220 修过一次不彻底，2026-10-25 三连复现） | 该用例已 `@Ignore` + `@Test(timeout=180s)`；恢复前别动；若新测试出现同类挂死，同法钉 timeout 后隔离 |
| `classes.jar` 文件锁 / kspCaches 路径冲突 | 上一轮构建被杀，daemon 持锁 | `./gradlew --stop` 后重跑（完成文档 §6 门禁纪律） |

### 2.3 测试"UP-TO-DATE 假绿"陷阱

```
症状：门禁绿，但某模块测试其实没跑（结果 XML 的 mtime 是几天前）。
病因：Gradle 增量——模块源码没变时 testDebugUnitTest 直接 UP-TO-DATE 复用旧结果。
排查：ls -la <module>/build/test-results/testDebugUnitTest/*.xml 看 mtime 是否为本次。
修复/预防：声称"某模块测试通过"前核 XML mtime；想让其真跑用 --rerun-tasks
或改该模块任意源码。历史教训：E228 门禁绿是复用 17:39 旧结果，E230 改了
scanner-local 源码才迫使真跑，暴露 bench 挂死。
```

---

## 3. 错误目录 A：构建 / 测试

### E-B1 管道截断造成假绿
```
症状：gradlew ... | head 出绿，实际失败。
病因：head 提前退出触发 SIGPIPE，退出码被 SIGPIPE 顶替，|| / && 判断失真。
修复：门禁命令不用 | head；要看尾部用重定向到文件后 tail 文件。
预防：铁律 R1。git bash 下 2>&1 | tail 同样有缓冲问题（见 E-B2）。
```

### E-B2 管道缓冲让进度不可见
```
症状：后台 gradle 的输出文件 0 字节，不知道是没跑还是卡死。
病因：`cmd | tail -N` 中 tail 全量缓冲，进程结束前一个字节都不写文件。
修复：`cmd > /tmp/xxx.log 2>&1` 直接重定向（无中间管道），进度实时可见。
预防：长任务一律重定向，不用管道过滤器包后台命令。
```

### E-B3 首跑极慢 vs 卡死分不清
```
症状：冷 daemon（--stop 后）全量门禁 10min+。
病因：冷启动全量编译本就慢（历史 2.5–5min 为热缓存）。
排查：按 §2.1 用进展指标+CPU 采样区分"慢"（CPU 持续增长）与"死"（ΔCPU≈0）。
预防：不要一见慢就杀；也不要一见慢就等——5 分钟判活一次即可分辨。
```

### E-B4 模块单测过了、全量挂
```
症状：单跑某模块绿，全量 test 挂死或红。
病因：跨模块批跑时序不同（E220 实录：单跑恒过、批跑偶发挂）。
排查：隔离复现 → jstack（§2.1②）。
修复：按根因修（E220 的 dispatchers 注入）；修不动则 timeout + @Ignore +
重开观察项（诚实记录，不静默删测试）。
```

### E-B5 结果统计脚本读到陈旧 XML
```
症状：统计出的套件/项数与本次运行对不上。
病因：build/test-results 里混着历史运行的 XML（UP-TO-DATE 不刷新）。
修复：统计前核 mtime，或 clean 后统计；引用读数时注明轮次。
```

### E-B6 APK 字节相同 ≠ 没编译（E231 实录）
```
症状：改了源码，两轮 assembleRelease 字节数一模一样（本会话两度出现），
怀疑"改动没进包"。
病因：R8 + 打包是确定性输出——同输入同字节，体积差不是"编没编"的判据。
排查：不解包猜。python zipfile 读 dex 找本次新增字符串（新 UI 文案最可靠）：
  import zipfile; any('新文案'.encode() in z.read(n) for n in z.namelist() if n.endswith('.dex'))
配合 APK mtime + 门禁日志 executed 任务数判断新鲜度。
预防：验证"装上的是新包"一律用内容判据；完成文档写体积读数时同字节
不等于异常（与"同字节用哈希+mtime+全量 executed 三件套核对"口径互证）。
```

---

## 4. 错误目录 B：Git / 提交

### E-G1 git add 被 .gitignore 拦截
```
症状：git add screenshots/e230-.../ 报 "paths are ignored"，commit 中止。
病因：screenshots/ 有意不入库（证据图本地留存，见 .gitignore L50 注释）。
修复：从暂存清单去掉该路径，evidence .txt 已含读数即可；不使用 -f。
预防：铁律 R3；提交前列 git status --short 复核暂存面。
```

### E-G2 提交信息规范
```
公式：<type>(<scope>): E<批次号> —— <一句话标题>
  body：分点写"做了什么/为什么"；门禁读数；取证读数；边界（没做什么）。
type：feat/fix/docs/perf/test/refactor；scope 用模块域（player-ui/danmaku/
  library,scanner/codec,probe）。
案例：fbc05c2、cb9ae9d、3dcb553。
```

### E-G3 一个逻辑批次拆多个 commit
```
公式：同批多任务（如 E229+E230）按任务分 commit；文档三件套随最后一个
commit 入库（消息里注明"含 Exxx 条目随批入库"）。避免把无关改动混进同一 commit。
注意：git add -p 交互式不可用——按文件粒度分批。
```

### E-G4 任务条目方向存疑
```
症状：条目文字双向可解（"移除 X 二次确认"= 删确认 or 加确认？）。
排查：git log -S "条目原文" -- docs/任务文档.md 找入库 commit → git show 看
上下文；再看同列兄弟项是增是减；再看现状代码有没有这个东西。
案例：E230——E183 时点也无确认弹窗 + 兄弟项皆为增项 → 判定"要加确认"。
```

---

## 5. 错误目录 C：ADB / 设备取证（Windows + Git Bash）

### E-D1 MSYS 路径改写
```
症状：adb shell cat /sdcard/x.xml 报 "cat: C:/Program Files/Git/sdcard/... 不存在"。
病因：Git Bash 把 /sdcard 当 Unix 路径改写成 Windows 路径。
修复：设备侧路径放进引号由设备 shell 解析：adb shell "cat /sdcard/x.xml"。
预防：凡 adb shell 带绝对路径参数，一律整段双引号。
```

### E-D2 深链命令的 & 被吞
```
症状：am start -d "...&fs=1" 的 fs 参数丢失（E218 实录：fs 深链从未被覆盖过）。
病因：& 被设备端 shell 当后台分隔符。
修复：adb shell 'am start -a android.intent.action.VIEW -d "完整URI含&" 包名'
——外层单引号、URI 内层双引号，整条命令设备侧求值。
```

### E-D3 uiautomator dump 落点
```
症状：dump /sdcard/xx.xml 后 cat 找不到文件。
病因：部分镜像/时点 /sdcard 写入异常。
修复：统一 dump 到 /data/local/tmp/xx.xml。
```

### E-D4 坐标提取必须取中心点 ★
```
症状：点"取消"却触发了"移除"（2026-10-25 实录：误删了扫描目录）。
病因：bounds="[x1,y1][x2,y2]" 的正则取出了角点（x2,y2），落在相邻按钮命中区。
修复/预防：一律取中心：
  center = ((x1+x2)/2, (y1+y2)/2)
  提取脚本：grep -o '[0-9]\+' 取四个数后 awk 求中心；回显坐标再 tap。
```

### E-D5 input tap 空参数
```
症状：input tap 抛 IllegalArgumentException: Argument expected。
病因：坐标变量为空（上一步提取失败）直接拼进命令。
修复/预防：tap 前回显坐标（echo "tap $X $Y"）；脚本里判空再执行。
```

### E-D6 uiautomator XML 是单行
```
症状：grep 按行匹配拿不到节点。
修复：tr '<' '\n' 先拆行再 grep。
```

### E-D7 UI 自动化导航误触
```
症状：一串盲点后前台变成别的应用（实录：误开第三方播放器）。
修复：input keyevent 3 回桌面 → am start -n 包名/.MainActivity 重进 → 重新 dump。
预防：每步 tap 前以最新 dump 的节点为据；不凭记忆估坐标连续操作。
```

### E-D8 媒体键不走应用视图层
```
症状：input keyevent 85/90/89 的效果与代码里的键表不符。
病因：系统在输入管线层截走媒体键发给活动 MediaSession（media3 默认档：
FF +15s / RW -10s），不经过应用窗口。
处置：取证时区分两条通路——媒体键读 dumpsys media_session（会话通路）；
J/K/数字/括号等普通键走视图通路。两条通路档位差异如实记录（E228 边界）。
```

### E-D9 组合键注入不了
```
症状：Shift/Ctrl 组合键无法用 input keyevent 注入。
病因：input 无 meta 参数（审查文档 §7 #1 封存）。
处置：组合键验证留给仪器测试构造 KeyEvent 或实体键盘；单键照常注入。
```

### E-D10 装机与数据
```
install -r 覆盖安装保留数据/权限；签名不符须卸载重装（QA 数据可弃才卸）。
release 非 debuggable：应用 internal filesDir 无法 adb pull（E216 教训）——
取证产物放 external filesDir（/sdcard/Android/data/<pkg>/files/）。
```

### E-D11 深链播放夹具的入库
```
推 /sdcard/Movies 后 MediaStore 常自动入库；查 id 用：
adb shell "content query --uri content://media/external/video/media --where \"_display_name='x.mp4'\""
（projection 参数在这台设备上报 Invalid column——不用 projection。）
```

### E-D12 adb 注入双击的 40ms 下限（E231 实录 ★）
```
症状：input tap ×2 / motionevent 连发后双击不触发；单击行为时灵时不灵。
病因：两头都有窗——两次独立 `input` 进程间隔可能 >300ms（doubleTapTimeout，
被判成两次单击）；而一条 shell 里连发（每段仅 ~20ms）又低于 doubleTapMinTime
=40ms，识别器（isSecondTapValid）显式拒掉过快的第二次按下。
修复：一条 shell 内注入、中间 sleep 0.1：
  adb shell "input motionevent down X Y; input motionevent up X Y; sleep 0.1; \
             input motionevent down X Y; input motionevent up X Y"
预防：手势取证的注入间隔写 100ms；"注入了没反应"先查时间窗，再查落点（E-D13）。
```

### E-D13 取证点击的浮层/前台陷阱 ★（E231 实录）
```
症状：坐标正确、注入正常，但界面"没反应"；或状态读数自相矛盾。
病因（四类，按出现频率）：
  ① 屏上有浮层（信息卡/菜单/系统弹窗）把事件全吃掉——点全落在浮层上；
  ② 播放器控制层可见时，单击被"收控制层"消费，双击/拖动到不了舞台层；
  ③ 中途按了返回键，播放页早被退出，后续点击全打在库页/迷你条上
     （迷你条的「暂停」desc 会混进 grep 计数，造成"控件在"的假象）；
  ④ 坐标按旧 dump 的版式算——转屏/分栏/浮层开合后坐标漂移（同 E-D4/E-D7）。
排查：每步先 screencap 肉眼确认前台与浮层，再操作；
  播放状态判定用 dumpsys media_session 的 PlaybackState（PAUSED/PLAYING），
  不用 UI 猜；区分播放形态用形态特有 desc（全屏有「停止播放」，内嵌没有；
  迷你条与全屏都有「暂停」——计数前先确认在哪个形态）。
预防：连续 UI 操作每步以最新 dump/截图为据；一次取证的点击序列写成
  "dump → 算中心 → tap → dump 校验"的闭环，不凭记忆连点。
```

### E-D14 adb push 的设备路径同样被 MSYS 改写（E231 实录）
```
症状：adb push x.mp4 /sdcard/Movies/ 报
  "failed to copy ... to 'C:/Program Files/Git/sdcard/...'"。
病因：E-D1 同源——MSYS 路径改写不只作用于 shell 内参数，push/pull 的
  设备侧路径照样中招。
修复：MSYS_NO_PATHCONV=1 adb push x.mp4 /sdcard/Movies/
预防：凡 adb 命令（shell/push/pull/install）带设备侧绝对路径，
  一律 MSYS_NO_PATHCONV=1 或整段引号（E-D1），两招都行但要有一招。
```

### E-D15 install -r 签名不符会静默失败（E233 实录 ★）
```
症状：模拟器 install -r 之后应用"装上了"，但行为还是旧包（旧弹框/旧交互）——
  首轮 E233 删除测试据此得出了错误结论（"SAF 删除也弹系统框"实为 10-22 旧包的
  createDeleteRequest）。
病因：INSTALL_FAILED_UPDATE_INCOMPATIBLE（新包与既有安装签名不符）时 install
  失败、exit code 非 0，但输出常被 `| tail -1` / 忽略 $? 遮盖成"看起来成功"。
修复/预防：装机后先核对——
  adb -s <dev> shell "dumpsys package <pkg> | grep lastUpdateTime"
  时间不是本次即未更新。签名不符的 QA 机（模拟器）卸载重装（数据可弃）；
  真机与发布包同签名时 -r 正常。另：本轮首测误导也提示"行为与代码不符先查装机
  新鲜度"，与 E-B6（APK 同字节判新鲜度）是同一族纪律。
```

---

## 6. 错误目录 D：Shell / Windows

### E-S1 后台任务监控
```
后台命令（run_in_background）的进度读输出文件（Read 工具），不盲等 TaskOutput；
TaskOutput 长阻塞会被交互打断，打断不等于任务失败——以输出文件为准。
```

### E-S2 复杂引号嵌套写不动
```
python -c "…" 里再嵌引号极易碎。修复：改写临时 .py 文件执行，或 heredoc。
教训实录：统计脚本、JSON 处理均落临时文件最稳。
```

### E-S3 路径含空格（"None Player"）
```
所有 shell 引用带引号："D:\Code\Player\None Player\..."；git 路径参数同理。
```

---

## 7. 错误目录 E：子代理 / 并行

### E-A1 子代理启动失败：model concurrency limit exceeded
```
症状：Agent 工具派出即失败（2026-10-25 三连：69s/91s/35s 后同错）。
病因：账户/计划的单模型并发上限——主会话已占一个实例，第二个起不来。
处置公式：
  重试 ≤1 次 → 仍失败即**主会话接管**，不第三次重试；
  如实向用户说明（不假装并行）；任务不丢，按原任务书自己做。
```

### E-A2 并行写码会脏正在跑的构建
```
症状：门禁构建运行中，另一执行者改源码 → 编译读到半写文件/结果失真。
规则：**单构建者原则**——构建运行期间，其它执行者只做只读调查
（读代码/读日志/写报告），等构建收尾再落盘改码。
```

### E-A3 并行 gradle daemon 互锁
```
两个执行者同时跑 gradlew → daemon 锁/cache 冲突。
规则：全仓库同一时刻只允许一个 gradle 调用链；子代理任务书里显式写
"禁止运行 gradle"。
```

---

## 8. 错误目录 F：工具调用

### E-T1 Edit 前必须 Read
```
症状：Edit 报 "File has not been read yet"。
修复：先 Read 目标区间再 Edit。大文件用 offset/limit 分段读。
```

### E-T2 old_string 不匹配 / 不唯一
```
症状：Edit 报 not found 或 multiple matches。
修复：把上下文多带几行让串唯一；注意 Read 输出的行号前缀要剥掉；
中文注释里混入的空格/全角字符逐字核对（实录：注释里"大 小写"多一空格）。
```

### E-T3 修改后自读复核
```
规则：连续 Edit 同一文件后，重读改动区确认语法闭合（括号/KDoc/悬空注释）。
实录：一次 Edit 误删函数体留下悬空 KDoc，靠重读发现修复。
```

---

## 9. 诊断命令速查（可直接粘贴）

```bash
# 判活：CPU 采样 ×2（间隔 10s）
powershell -NoProfile -Command "Get-Process java | Select Id,CPU,StartTime | Format-Table -AutoSize"

# 认进程：谁是 daemon / 谁是测试 worker / 谁是客户端
for pid in <pids>; do echo "=== $pid ==="; jstack $pid | grep -m1 "GradleWorkerMain\|daemon\|DaemonClient"; done

# 测试挂死定位
jstack <worker> | grep -A25 '"Test worker"'
jstack <worker> | grep -B3 -A25 'noneplayer'     # 应用帧在等什么

# 门禁收数（test / lint / 体积）
python - <<'EOF'
import glob, re
s=t=f=e=sk=0
for x in glob.glob('**/build/test-results/**/TEST-*.xml', recursive=True):
    m=re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', open(x,encoding='utf-8').read())
    if m: s+=1; t+=int(m.group(1)); sk+=int(m.group(2)); f+=int(m.group(3)); e+=int(m.group(4))
print(f'suites={s} tests={t} skipped={sk} failures={f} errors={e}')
EOF

# 设备 UI 节点中心点（tap 前回显）
adb -s <dev> shell "uiautomator dump /data/local/tmp/ui.xml"
adb -s <dev> shell "cat /data/local/tmp/ui.xml" | tr '<' '\n' | grep 'text="目标"' \
  | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' \
  | grep -o '[0-9]\+' | tr '\n' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'
```

---

## 10. 取证与文档落盘公式

```
证据文件：docs/evidence/e<批次号>-YYYY-MM-DD-device.txt
  结构：设备/安装/夹具 → 分节读数表（操作 → 读数）→ 稳定性（FATAL 计数）
        → 边界（没验证什么、为什么）。
截图：screenshots/e<批次号>-YYYY-MM-DD/（本地留存，不入库——R3）。
文档：完成文档新条目（结论+测试+门禁读数+取证读数+边界）；
      任务文档删/改条目；§6 门禁基线数字更新。
口径：测试数注明基线增量（"基线 154/1075 +5"）；APK 同字节时注明"与 Exxx 同字节"。
```

---

## 11. 本仓库已知"看似 bug 实为裁定/现状"清单（别当缺陷修）

- `L` 键 = 锁定（不是 +10s，E228 裁定）；倍速会话内生效不持久（D4）。
- 删除确认/清空确认已有 BackHandler（A27/E220），别重复加。
- `READ_MEDIA_VISUAL_USER_SELECTED` 判据已全链实现（REV-13）。
- 无 idx1 AVI `isSeekable=false` 是既定口径（超级索引路径已由 E227 接上）。
- 双击 = 播放/暂停（E231 裁定，三区一律；键盘 ±5s/±30s 不变）；「双击步长」
  设置条目已退役，AppSettings 里的流程与键只作历史兼容，勿再引用。
- 拖进度条不暂停（E231 裁定，边拖边播）；`onScrubStart` 不得再 pause，
  右栏/内嵌（InlinePlayerSurface）与全屏（PlayerScreen）两处实现同口径。
- release APK 统一落 `None Player/app/release/`（E231 起，构建收尾自动拷贝）。
- 全屏按视频方向分两种（E232 裁定）：竖屏视频进竖屏全屏（`OrientationLock.PortraitFullscreen`），
  横屏/未知进传统横屏全屏；"全屏 ≡ 窗口横向"已不成立——在两侧都判断全屏前先看这个。
- 迷你条耳机钮是**单向入口**（E232 裁定，只进音频）；退出仅听走音频页「切回视频」，
  别再给耳机钮加"切回视频"的反向语义。
- 删除只有**一层应用内确认**（E232 裁定，无 `createDeleteRequest` 系统框）；API 30+ 无
  「所有文件访问」且文件非本应用属主时，应用**有意不发起删除**（MediaProvider 必弹系统
  同意框），直接给应用风格引导卡（E233，去开启 all-files）；有权限时走 SAF 树/直删；
  新授权目录按 READ|WRITE 持久化，26–29 档申请写存储权限。
- 音频模式队列内切集沿用当前模式（E232）：`carryAudioModeTo` 是唯一落点，
  别再在 `observeEngineIndex`/`selectQueueIndex` 里按下一集记忆重算。
- 睡眠定时四档（15/30/60/播完当前）与双键落盘是 E234 交付口径：`SleepPreset` +
  `startSleepTimer/cancelSleepTimer` 是唯一入口；「播完当前」期间连播被**有意关闭**、
  结束后按设置真值恢复，别当缺陷；标签必须走 `rememberSleepTimerLabel`（不在组合期读墙钟）。
- 附录 A（任务文档末节）列了全部"明确不做"，开任务前先查，防止重开已裁定项。

---

## 12. 错误目录 G：Compose / 外壳 / 播放链（E231 增补）

### E-C1 分档分支互换丢 rememberSaveable（movableContentOf 收口）★
```
症状：竖屏进文件夹 N 层 → 转横屏，直接退回总文件夹根层（层级/筛选/滚动全丢）。
病因：外壳按档位写的 if/else 两套版式（底部栏 Scaffold ↔ Rail Row）互换时，
  公共子树（内层 NavHost）被整体销毁重建——rememberSaveable 只在导航栈
  saveState/restoreState 时存档，普通组合销毁无人收。
排查：① 复现前先查档位判定（NpWindowInfo）：Compact↔非Compact 才会翻分支，
  平板两档都非 Compact 时复现失败——用 `wm density 640` 把竖屏压进 Compact
  等效复现（真机 60dd2f94 实录）；② 状态归属谁——rememberSaveable 在
  Tab 页组合里，外壳分支翻转对它就是"页面被拆了"。
修复：movableContentOf 包住公共子树（内层 NavHost），两分支间携带状态搬移；
  落点 modifier 经数据类参数传入，不闭包直捕（navHost lambda 每次重组换新
  实例，闭包会钉死首帧的动画参数）。
预防：外壳级"两套版式共享的内容"一律 movableContent；新增形态分支前先审
  页内状态的归属与存档路径。
```

### E-C2 同一语义多处实现——修"行为"先 grep 全部实现点 ★
```
症状：修了 PlayerScreen.onScrubStart 的"拖动暂停"，真机横屏右栏拖动中途
  仍是 PAUSED——首轮修复被真机抓包。
病因：同一行为（拖进度条的生命周期）在右栏/内嵌播放面是独立实现
  （InlinePlayerSurface 自带一份 scrub 状态机），只修全屏那份等于没修全。
排查：改语义前先把关键回调的全部实现点找齐：
  grep -rn "onScrubStart = \|if (scrubWasPlaying) player.pause()" plugins/*/src/main
  （赋值点 + 同名局部函数 + 各自的 pause/play 配对都要过目。）
修复：两处同口径改齐，注释互指（"与 PlayerScreen 同口径，E231"）。
预防：行为级修改的验收必须覆盖各形态（全屏 / 右栏 / 内嵌 / 仅听）——
  单形态通过不算过；真机取证时专门换形态复测一次。
```
