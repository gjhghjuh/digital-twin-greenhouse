# 数字孪生智慧大棚项目交接

更新日期：2026-10-29（北京时间，G5当前范围已认可并完成交付收口）。项目根目录：`D:\数字孪生智慧大棚`。

本文件保留已验收成果、历史证据与踩坑记录。上一轮用户已认可实际安装的手动场景 build03，并暂停跨端能力。本轮用户新请求“阅读说明书，完成未完成的任务”，恢复推进；尚未收到是否解除跨端暂停的澄清，暂保留该范围，完成现有功能的 G5 源码检查、必要修复和构建。先前G5 build02已安装且部分真机检查完成；2026-10-29用户更换同型号ESP32并要求重新烧录、换WiFi及可视化，本轮新板进度见第十二节。用户已明确认可当前G5版本；AUTO等跨端能力已明确不纳入本次工作，当前范围交付资料已就绪。

D7 是正式过程主记录，D1—D6 保存需求设计。本轮已将场景 build03 的已有认可同步到正式验收 JSON，D7 第四十一节记录最新认可与 G5 推进；旧段落中的“待认可”保留其历史写入时点。文档里的历史提示词只作上下文，当前执行依据是用户本轮新请求。

## 1. 当前任务与停止位置

- 已完成 **M0、G1、G2/M1、G3/M2、G4 当前前端范围、G4E 的单水泵 MANUAL 离线预约与两个场景，以及已认可的 G5 当前范围**。原项目被排除的跨端能力未实现，当前范围交付资料已就绪。
- 最新已构建 Android 包：**0.5.0-g5-r01，versionCode 9，build02**，修复历史回放旧任务串入与无设备永久加载，已实际安装、身份一致；2026-10-11用户暂停后，2026-10-29已继续新板与部分G5验证。上一轮已实际安装且获认可的仍为 **0.4.2-g4e-scenes-r01，versionCode 8，build03**；当时手机 base.apk 哈希与交付包一致。本版已实际安装并经用户认可；2026-10-29新板、手机联调及范围决定见第十二至十四节。
- 离线预约首组：用户在 2026-10-11 回复 **“认可了，下一步”**，接受 **0.4.1-g4e-offline-r01 build02**。
- 手动场景组：用户在本轮明确回复 **“认可了”**，接受上一轮交付的 **0.4.2-g4e-scenes-r01 build03**。不再重复索取该版本视觉认可；此认可不补成尚未收到的本轮通风 LED/OLED 人工观察，也不扩为全项目验收。
- 原七个大验收关口中，离线预约、MANUAL 场景和 G5 当前范围已认可；原余四关中三项跨端关用户明确不做，当前已完成已认可范围的交付收口。这是五个可验收成果阶段，不是五次改代码或工时承诺。
- **G5修复、构建、安装及部分真机检查已完成；2026-10-29已继续验证，当前G5版本已获用户认可；原项目被排除的跨端功能未实现。** AUTO/设备规则/设备报警跨端能力用户明确不做；原项目完整 K/M3 标准不冒认完成。

### 最新认可与正式记录的时点差异

D7 第三十七至四十节记录预约/场景的技术实现、证据和限制，第四十一节补记已有认可与本轮 G5 推进。本轮场景 `acceptance-round01.json` 已同步认可、pending 为空、剩五关；`ui-review-build03.json` 也追加同一版本的用户原话。原技术证据及通风 LED/OLED 人工观察未收到的限制保留，旧 stageRecord 哈希继续标识原技术写入版本，不偷换成最新过程哈希。

本轮修改前基线核对：D7 为 **131303 字节、body 直接段落 570（含表格全文 w:p 为 620）**，SHA256 `f834220a374da226ca77634701e63e7024940cebfda008dff5dedcc870bf3bc7`，已保存的 87 页版式核验对应此哈希。修改前场景 `source-build03-hashes.json` 的 **59 份文件全部与本轮起始 Android 源码一致**，两组交付 APK 哈希均核对一致。旧 G4/预约源码清单只证明各自历史版本，不能要求当前增加场景后的源码仍与它们完全相同。

2026-10-11旧板联调最后手机记录为 LIVE/SUCCESS/ONLINE/FRESH、三路 OFF，旧串口末次 snapshot251；COM5 和归档状态仅为历史记录。2026-10-29已更换新板并通过 COM7 与 vivo 手机联调，最新换板与验收证据见第十二、十三节。续接时重新发现端口和设备，不把一次记录当作持续在线保证。

## 2. 已完成的内容

| 阶段 | 已完成范围 | 证据入口 |
| --- | --- | --- |
| M0 | 真实温湿度、OLED、三路外接 LED、土壤软件模型、ADC 光照档位；结构化串口、CSV/SQLite 归档；OneNET TLS/MQTT 上下行、关联命令结果、重复/过期/旧启动拒绝、离线有效意图、NVS 去重、断网恢复、USB 重连启动 | D7 第十八节；`验收记录/M0`；`项目数据/development/M0-20260928` |
| 3D 资产 | 用户已交付并验收完整 3D 大棚。当前使用 greenhouse-v2 的 GLB、节点映射和相机预设，Android 已接入 | `项目源码/twin-assets/greenhouse-v2`；`验收记录/3D-V2` |
| G1 | 已认可首页主题；真实 OneNET 查询、冷启动 UNKNOWN、唯一前台 LIVE 同步、Room 去重、配置持久化；设计样例与正式历史隔离 | D7 第二十三、二十四节；`验收记录/Android/G1/round-03/delivery-index.json` |
| G2/M1 | 设备控制及完整孪生；手机→云→ESP32→OLED/三路 LED 实际闭环；对象点选、旋转缩放、剖切/内部/俯视、标签开关；共享 3D 资源与切页恢复；用户认可 | D7 第二十七节；`验收记录/Android/G2/acceptance-20261004/acceptance-audit.json`；`G2/round-01/delivery-index.json` |
| G3/M2 | 数据中心、CSV 预览及确认导入、记录身份去重、真实历史回放/缺口、播放速度、业务层禁控；Vico 折线/柱状/混合样式切换；用户认可 build11 | D7 第二十九节；`验收记录/Android/G3/round-01/acceptance-delivery-index.json` |
| G4 | 当前/历史报警、确认/恢复区分、环境建议接既有控制面板、完整设置、阈值校验与持久化、异常分类、刷新/动画、宽度及字体适配；用户认可 build06 | D7 第三十三节；`验收记录/Android/G4/round-01/g4-build06-final-acceptance.json` |
| G4E 离线预约首组 | 单水泵固定 60 秒 MANUAL 离线预约；原包冷启持久恢复、同包重发、原 boot 有效执行、过期及旧 boot 拒绝、迟到结果；用户认可 build02 | D7 第三十七、三十九节；`验收记录/Android/G4E/offline-pump-round-01/acceptance-round01.json` |
| G4E 手动场景组 | 灌溉 Pump ON；通风 Fan ON 确认后 Lamp OFF；逐项关联、部分结果、停止后续、后台/冷启不续发；用户本轮认可 build03 | D7 第四十节；`验收记录/Android/G4E/manual-scenes-round-01/acceptance-round01.json`、两个审计 JSON；最新认可补记于本交接第一节 |

### G4 的具体结论

- 本地规则：温度低于 **10℃** 或高于 **35℃**；空气湿度低于 **40%** 或高于 **85%**；土壤低于 **30%**；光照低于 **2 档**。首个可信有效新样本越界即警告，回到含边界的安全范围即恢复。数据陈旧阈值 **60 秒**。
- 报警“确认”只表示查看，不等于恢复。规则修改以 `RULE_CHANGED` 结束旧规则报警，不伪造数值恢复。
- 本地监测只在手机前台实时模式运行，回放/后台暂停；不承诺后台推送，不自动控制设备。
- 已实际验证 SIM 低土壤报警、确认保留、泵命令闭环及新样本恢复；EXP 0 档低光报警，调节途中首次安全样本为 **3 档**，之后 **2 档**持续安全。不能写成恰在 2 档恢复。
- 已实际验证约 120 秒设备断网期间同 boot 连续采样，60 秒风扇请求超时、重连后 `EXPIRED` 拒绝，风扇保持 OFF。查询失败、设备离线、数据陈旧和命令超时分别表达。
- 已验证真实手机查询失败/恢复、阈值非法输入拒绝、冷启动持久化、回放/后台暂停、唯一轮询、动画开关、360/约 430dp 与字体 1.3。
- Room 版本 2→3 后，原 6432 条主记录逐 19 字段、5220 条归档表示逐 10 字段比较，缺失及改动均为 0。
- 温度和太阳图标的完整光晕必须保留。G4 是本地规则建议，不是 AI 预测或设备 AUTO。

### G4E 离线预约的具体结论

- 仅水泵、固定 60 秒 MANUAL，不是未来钟点预约。普通实时开关仍要求前台 LIVE、成功查询、ONLINE+FRESH；离线预约要求本次云查询成功且明确 OFFLINE，持有最后可信设备身份/时间，未解决或异包不存在。查询失败和陈旧都不能冒充已证实离线。
- 确认窗口、锁内、实际写入前核对 deviceId/bootId/configVersion，不换新 boot 补发。云端保存只代表等待结果，Reported/3D 不提前变为运行。重发保留原八字段、commandId 与 60 秒期限；取消界面等待不撤回云意图。
- build02 实测：有效开启同一意图两次云保存，对应一次实际动作；冷启原 ID/boot/期限不变。过期关闭同包重发仍 EXPIRED，LED 保持亮/OLED ON；原期限内执行关闭、App 到期后再查看仍确认 EXECUTED 且 late=true，用户确认 LED 灭/OLED OFF。
- 旧 boot 开启预约返回 BOOT_MISMATCH、无动作，App INTERRUPTED，用户确认灭/OFF。**受控重启发生在原期限后 64.038 秒**，样本同时旧 boot 与过期；固件先检查 boot，不能登记为“有效期内旧 boot”的独立隔离测试。同包重复保存的一次动作也不等于独立触发了 COMMAND_DUPLICATE 分支。
- ControlJournal 原严格 version2/purpose 实现及已验收迁移保留；场景在此基础增加独立场景记录，不能重新迁移、清除旧数据或另造预约链路。

### G4E 手动场景的具体结论

- **IRRIGATION 灌溉**：只发送 Pump ON，不发送 Lamp/Fan。**VENTILATION 高温通风**：Fan ON 完整关联确认、Desired 清空且同身份前台 ONLINE+FRESH 后才发送 Lamp OFF，不操作 Pump。点击启动，MANUAL；场景名称不表示阈值自动触发，也不证明真实灌溉或降温效果。
- 每步独立 commandId 和原八字段 MANUAL 包，目的仍 REALTIME，60 秒 TTL。冻结 device/product/boot/config，seq/stateVersion 可正常推进；当前包和场景在同一 DataStore 提交持久化后才公开/发送。复用唯一同步、现有控制锁、云查询与关联确认。
- 后台、进程恢复、拒绝/未知/超时、身份或上下文变化、用户停止会停止未发送步骤；不撤回已发包、不回滚已完成状态、不自动续发。已发包可继续查询/按原 ID 重试核实；历史步骤只读，不接入当前包重发按钮。
- 结束或停止后的场景状态按实际确认完整度计算：全确认 SUCCESS、部分 PARTIAL、零确认 STOPPED；停止后的全齐结果不会恢复 RUNNING。UI 显示“原停止记录”，避免停止瞬间的“仍需核实”被误读为当前结果。
- **build02 实测**：正常灌溉 1/1、泵 ON 另两路 OFF；通风准备 Pump ON/Lamp ON/Fan OFF，Fan 确认后 47 毫秒创建独立 Lamp OFF 包，设备状态版本 2→3→4，最终 2/2 且 Pump 保持 ON。
- **build02 后台冷启实测**：Fan 已云保存、Lamp 尚无包时退出，恢复原 ID/TTL，稳定 PARTIAL 1/2，Lamp request=null，没有续发。此样本 Fan 原 ON、Lamp 原 OFF，未发送结论来自无第二包及无对应事件，不能把 Lamp 保持 OFF 当作 Lamp ON 保留的输出测试。单步停止后原包补齐 SUCCESS 1/1，保留停止经历；查看仍在期限内，late=false。
- **build02 门禁实测**：手机查询 FAILED/设备 UNKNOWN 时禁用新场景；真实回放中直接调用业务入口被 scene-blocked 拦截，没有创建新包。它们是入口验证，不是在途场景查询失败验证。
- **build03 实测**：安装后恢复已存 1/1 及停止经历；实际点“停止后续步骤”，Fan 原包成功后 PARTIAL 1/2，Lamp 未创建。之后普通 Pump OFF/Fan OFF 关闭三路，原场景对象逐字段保留，当前普通请求独立保存。
- build03 相对 build02 **仅 ManualScenePanel 历史文字标注改变，后端文件哈希相同**。正常、后台冷启、失败与回放路径在 build02 测；build03 另测恢复、停止、关闭与适配，不称全部在最终 build03 逐一重跑。
- 360dp、约 430.8dp/font_scale1.3 的确认、正常/恢复/部分结果原图均实际检查。最后恢复物理密度560、字体1.0、移动数据1。本轮场景用户视觉认可已取得，但通风成功时请求的 LED/OLED 人工状态回复未收到，继续保留此证据限制。

### 最新交付与本轮收口

- [当前场景最终 APK](D:/数字孪生智慧大棚/验收记录/Android/G4E/manual-scenes-round-01/智慧大棚-G4E-scenes-0.4.2-round01-build03.apk)：**74642815 字节**，SHA256 `e943689fadc0c0a985079a35c30df7d0e03c6b23fa8e2d8056b39698773787c0`；当时实际安装 base.apk 相同，本轮只读重新核对交付文件一致。
- [场景交付身份](D:/数字孪生智慧大棚/验收记录/Android/G4E/manual-scenes-round-01/delivery-build03.json)、[当前源码身份](D:/数字孪生智慧大棚/验收记录/Android/G4E/manual-scenes-round-01/source-build03-hashes.json)、[场景结果汇总](D:/数字孪生智慧大棚/验收记录/Android/G4E/manual-scenes-round-01/acceptance-round01.json)、[正常与后台审计](D:/数字孪生智慧大棚/验收记录/Android/G4E/manual-scenes-round-01/evidence-normal-scenes-review.json)、[最终安装版审计](D:/数字孪生智慧大棚/验收记录/Android/G4E/manual-scenes-round-01/evidence-final-build03-review.json)。该汇总已在本轮G5续接同步build03用户认可，技术证据和未收到实物观察的限制继续保留，不重复要求旧版本认可。
- [离线预约已认可 APK](D:/数字孪生智慧大棚/验收记录/Android/G4E/offline-pump-round-01/智慧大棚-G4E-offline-0.4.1-round01-build02.apk)：**74540428 字节**，SHA256 `8d852391109bf20e72127425a1ac11ed0410b8a77610e42062c924bffe2eef93`；[离线预约汇总](D:/数字孪生智慧大棚/验收记录/Android/G4E/offline-pump-round-01/acceptance-round01.json)、`delivery-build02.json`、`source-build02-hashes.json` 为此历史版本证据。当前手机使用包含预约能力的场景 build03，不为续接默认降级安装旧 APK。
- 两个 G4E 目录的 `device` 保存手机状态/XML/同步日志、当前原包与原始截图；场景 `ui-review-build02.json`、`ui-review-build03.json` 分别记录实际原图适配。不要把 JSON/截图的日期替代实际版本身份。
- 场景归档 `manual-scenes-round-01/serial-live`：event117—295/179条、snapshot109—251/143条，捕获范围内连续，无重复，SQLite quick_check=ok。最后 snapshot251、stateVersion9、boot `f9fd1af54fe6438cca4ca0f622e5f093` 三路 false；末行 USER_STOP，Windows 工具退出码1不登记为程序成功退出码，也不凭此否定已写盘数据。
- 离线预约归档 `offline-pump-round-01/serial-live`：原 boot event8—222、snapshot2—179，以及重启后 event1—19、snapshot1—11 范围内连续；原 boot event6/7 未捕获。场景与预约两轮之间的未采集时段保留，不能把多个范围拼成整个 boot 无缺口。
- [场景 build03 的 D7 版式基线核验](D:/数字孪生智慧大棚/验收记录/Android/G4E/manual-scenes-round-01/layout-review/qa.json)：87页，81页与已检查基线像素相同，6页重新实际看原图通过；对应第一节修改前基线哈希。本轮G5的D7更新及新版式核验另见G5目录，不把该87页结果套作新文件证明。

### G4 历史交付与归档（2026-10-04）

- [最终 APK](D:/数字孪生智慧大棚/验收记录/Android/G4/round-01/智慧大棚-G4-0.4.0-round01-build06.apk)：74704714 字节；SHA256 `a4cb01025369f7eff78b870a238cfc5cd05bab41852535c53890047f7a6c6209`。当时实际安装于 vivo，并核对手机 base.apk 相同；交接初版曾核对文件哈希一致。这是历史交付，不是当前安装版本。
- [最终验收](D:/数字孪生智慧大棚/验收记录/Android/G4/round-01/g4-build06-final-acceptance.json)、[128 项证据索引](D:/数字孪生智慧大棚/验收记录/Android/G4/round-01/g4-build06-final-evidence-index.json)、[源码身份](D:/数字孪生智慧大棚/验收记录/Android/G4/round-01/source-build06-hashes.json)。
- [原始真机录像](D:/数字孪生智慧大棚/验收记录/Android/G4/round-01/智慧大棚-G4-build06-真机联调.mp4)：89.262544 秒、630×1400；完整解码和六帧实际检查通过。抽帧覆盖历史/当前报警、建议和首页；设置与标准控制使用另存实拍/XML/状态验收。
- [D7 最终版式核验](D:/数字孪生智慧大棚/验收记录/Android/G4/round-01/D7-g4-final-layout-review/qa.json)：77 页通过；71 页与已检查版本像素一致，6 个变化/新增页重新实际查看。
- 2026-10-04 最后手机记录为 `LIVE/SUCCESS/ONLINE/FRESH`，三路 OFF，刷新 10 秒、动画 ON、本地监测启用。最后串口快照 seq6354、stateVersion23，bootId `df1fa5ecb639338a0896f89f4c1e44ea`，三路 MANUAL/OFF，EXP 光照 2 档、SIM 土壤 0%。低土壤报警继续保留是符合规则的结果。
- 串口归档器最后已正常退出，不存在需要续接的旧运行会话；不要复用旧工具 session ID。新端口、ADB 地址与设备在线状态必须重新发现。

## 3. 卡住的问题与验收限制

当前已交付的 G4、单泵预约及手动场景没有仍阻塞本组收尾的安装/视觉问题。跨端暂不做是用户当前范围选择，不是程序故障。以下欠项或证据限制仍保留，不能因此登记全项目验收：

1. OneNET 当前账号的实际 API 配额与属性历史保留期尚未从权益页核实。不能承诺永久云历史，也不要替用户购买服务。
2. 土壤为 **SIMULATION 软件模型**，光照为 **EXPERIMENT 0–7 实验档位**，不是土壤传感器实测或 Lux；不代表真实泵、风扇、灌溉效果。
3. G4 未人为制造所有真实温湿度越界端点、SENSOR 无效样本或 DataStore/DAO 存储损坏。相关错误分支的源码审查不能称实机故障测试通过。
4. 断网超时发生瞬间未拍截图，依据真实持久记录、同步日志与串口时间线。录像没有证明所有页面的完整操作过程。
5. 归档换轮有已知缺口，包括 seq5884、6036–6039、6220–6264，以及更早 5191→5764 的间隔。seq5884 的 SIM 恢复样本来自手机 LIVE，没有串口副本。两分钟断网采样连续的结论仅限实际 seq5924–5943。
6. AUTO、本地设备声光报警、设备规则配置及生效确认、AUTO数据历史跨端仍是欠项；离线预约与两个MANUAL场景已完成，不能继续列为未开始。G5及K/M3全面验收未做。
7. 离线旧 boot 样本同时过期，未隔离 TTL 内旧启动；没有独立触发 COMMAND_DUPLICATE。场景未实际制造提交前身份变化、存储损坏、外部并发、第一步已确认而第二步尚未创建的精确取消窗口、第二步超时、在途查询失败或到期后迟到标记。
8. 场景有设备 Desired 清理 CODE200，但 App 原始 Desired=null 查询响应没有单独留档；串行门禁不是服务端 CAS，不宣称并发外部写入无竞态。
9. 手机移动数据恢复后 ESP32 云快照曾延迟，经既有 LOCAL_DIAGNOSTIC pauseWifi 十秒后恢复；不能登记为自动网络恢复通过。OneNET 离线标记本身有延迟，120秒暂停不足以保证预约窗口，需等待本次成功查询明确 OFFLINE。

## 4. 已踩过的坑与不要回退的逻辑

| 问题 | 已确认原因或正确处理 |
| --- | --- |
| OneNET 原有两个产品不属于本项目 | 已新建专用产品及设备，复用现有项目产品，不能再次重建或改动旧产品。登录曾失效/被其他会话顶出，恢复后先核对保存结果。 |
| Android API 返回 10403 | 曾把 ESP32 设备密钥误当产品访问密钥。Android API 使用产品级资源对应访问密钥，设备上报使用设备密钥；不要交换用途或猜密钥。 |
| Wi-Fi 接入失败 | 原网络不可见；新热点曾限制新增连接。历史原因码 201 对应找不到 AP，5/205 曾伴随热点拒绝。先核实 2.4GHz 热点、接入限制及本地配置，不猜密码。 |
| USB 重插后 OLED 黑屏 | 当时只有串口重新打开，没有正常启动；人工 RTS 复位不算自动恢复。已实现并验收显式 USB 重连启动策略，用户确认 5 秒内亮起。不能将其写成无需启动策略的自然冷启动通过。 |
| 三路 LED 最初没有接线 | 板上红色电源灯不能作为泵灯。早期口头验收已更正；用户后来真实接好三路，M0/G2 实物逐路验收已完成。不要依据旧照片认定今天仍未接线。 |
| OneNET 2254 土壤步长拒绝 | 平台 float 范围 0–100、步长 0.01；独立 soilHumidity 标量已固定整数百分比上报，完整 snapshot 保留软件模型原始精度。不要改回产生精度步长拒绝的标量。 |
| vivo 安装/ADB 中断 | 每次更新可能需要手机当次外部来源安装授权；USB 曾反复断开，最终使用无线 ADB。安装成功须有系统结果和 base.apk 哈希，不把上传成功当安装成功。IP/端口不是固定值。 |
| 3D 反复切页内存增长与释放崩溃 | 共享模型和渲染资源，复用唯一 Stage、现有映射与生命周期；不要为每个页面另建 Engine、加载一份 GLB 或重复绑定动画。 |
| 3D 卡片遮挡及模型点选失效 | 已补标签显示/隐藏与对象拾取；相机手势必须在正确区域执行。此前对模型区域做页面滚动，实际旋转到底面，不能据此判定模型加载失败。 |
| G3 数据只显示点或直线 | 已改用 Vico，提供折线/柱状/混合切换。实际恒定数据仍可能是直线；缺口不能用虚构值或跨缺口连线美化。用户的 Chart.js 链接是视觉参考，当前实现并非 Chart.js。 |
| G4 build04 返回首页崩溃 | 导航图标传入空报警来源触发校验，曾被误判用户切应用。build05 按真实温度/水分/设备类别映射并保持校验；错误历史留存，不能再归咎用户。 |
| G4 保存、建议与连接测试竞态 | build06 分离操作错误与持久读取错误；保存等待监测消费同一规则；建议点击复核 ACTIVE、无错误、当前已加载规则和保存状态；连接测试排除点击前旧查询身份。 |
| 误判异常测试已通过 | HTTP 代理尝试没有产生 FAILED，不能计通过；实际移动数据关闭产生 ConnectException，设备应 UNKNOWN，不能伪装成已证明 OFFLINE。 |
| 时长、回放与控制误判 | 只累计相邻可信样本，不把暂停/缺口/重启补成连续。确认不是恢复，Desired/云端接受不是 Reported/实际执行。回放业务入口必须禁控，不能只禁用按钮。 |
| 文档渲染与录像核验 | 文档默认渲染曾因缺 LibreOffice 不可用，最终用独立隐藏 Word 只读导出并检查 PNG，只关闭自己创建的进程。视频默认 null 输出时间基产生重复 DTS，改用源 demux 时间基/passthrough 完整解码，原视频未修改。 |
| 120秒断网窗口与60秒预约不匹配 | OneNET离线标记更新较慢；等真实OFFLINE后才创建预约，重发不延长原期限。离线联调曾由用户暂时延长热点关闭，不能直接放松门禁。 |
| 迟到执行与到期未执行混淆 | 用设备执行时间和原期限判断历史成功，再标App查看迟到；不因当前OFFLINE/STALE抹掉已关联成功，不因云保存推断运行。 |
| 旧boot样本与过期混在一起 | 离线预约联调的重启在到期后64.038秒，固件先回BOOT_MISMATCH；只证明此组合拒绝，不能写TTL内旧boot单因素通过。 |
| 停止后全齐仍显示PARTIAL | 已统一按实际确认完整度计算，全齐SUCCESS且保留停止经历；isRunning只认RUNNING，补记成功不恢复编排。 |
| 停止瞬间文案误当当前结果 | build03把说明标“原停止记录”；当前已确认/未发送由各步骤独立展示，不能串改历史事实。 |
| 最后版本与旧版本证据混用 | 保留build02执行证据、build03仅UI变更的源hash对照及最终实测；不能把旧截图或后端同hash说成所有最终版路径都重跑。 |
| 手机截图不是合法PNG | vivo多显示器直接screencap曾混入警告；先查实际显示器，指定primary display并校验PNG签名，不用损坏图片作为验收。上一轮ID只作历史参考。 |
| 密度/字体切换导致页面重建 | 临时适配会触发生命周期；运行场景应据此停止，不误当继续执行。还原后等待页面稳定并重新读当前XML，不盲用旧坐标。 |
| Windows Ctrl+C退出码1 | 归档有USER_STOP且已结束；用SQLite检查、原始日志和捕获范围确认完整度，不将退出码1单独当作成功或数据丢失证据。 |

另有陈旧标记：`contracts/runtime.json` 内仍有 OLED/外接 LED 待确认字段。硬件后续真实验收以 D7 第十八节及证据为准；发现文档/契约冲突先列明，不凭单个旧标记推翻已验收工作。

## 5. 必须复用的实现

- **统一状态/查询**：Application 持有共享仓库；G1 只有一个前台 LIVE 轮询。`DESIGN_DEMO`、`LIVE`、`REPLAY` 隔离。进入实时先 UNKNOWN，失败不自动换成样例。
- **统一控制**：同一 ESP32 单控制包串行。保留 commandId、targetBootId、configVersion、TTL、同包重发、旧启动拒绝与持久去重；云端接受不等于设备收到或执行。M0 没有独立上传 RECEIVED 回执，NVS 内部 RECEIVED 不能点亮云端“已接收”。
- **离线预约与场景**：复用 `ControlPurpose.REALTIME/OFFLINE_RESERVATION`、严格 version2 控制日志和独立 version1 场景记录。`loadState()` 从同一 Preferences 快照读当前包/场景；同一提交先落盘再公开。冻结身份，下一步必须逐项完整确认及 Desired 清空；停止后核实原包，不新增自动恢复/后台编排或第三种控制模式。
- **历史/回放**：复用 Room、CSV 预览确认导入、记录身份及归档表示去重、真实时间缺口、独立 ReplayState。回放禁止 request/resend，退出重新同步。
- **报警/建议**：复用 MonitorRepository 与 alarm episode 持久化；建议仅进入 G2 标准确认面板，不另起执行器。G4 本地规则不能直接充当设备自动执行规则。
- **视觉/3D**：沿用用户认可主题、图标素材及完整光晕，复用 V2 GLB/映射/相机、共享资源和唯一 Stage。不重画首页、不重建模型或另造图表。

## 6. 下一步计划与不做范围

### 当前不做

用户在2026-10-29明确回复“不做跨端能力了，继续（还有活的话）”。AUTO、设备规则、设备本地报警及其跨端配置不纳入当前工作；已有MANUAL预约、场景和G5当前范围已获认可。原D1–D6中的扩展需求保留历史，不冒称已实现。只有用户之后明确改变范围时，才重新讨论对应设计。

同样不重复开发或验收已认可的预约/场景，不重画已认可UI、不重建3D/图表、不升级依赖、不新增未要求的测试脚本或项目说明MD，不购买云服务，不导出凭据。

### 原有五个关口（历史路线；当前范围已收窄）

| 剩余关口 | 要完成和验收的具体成果 |
| --- | --- |
| 1 参数与契约冻结 | 对照D2/D4/D5/D6及实际固件，明确每路MANUAL/AUTO、启停阈值/滞回、保护时长、无效输入、模式切换、重启及显式停止；设备声光报警生命周期、规则字段/configVersion与真正生效确认；先收口设计，再实现。 |
| 2 ESP32自动规则与本地报警 | 设备是唯一AUTO决策点；完成实际构建、必要烧录与归档，证明手机关闭或断网仍按冻结规则工作，重启、异常输入、停止和报警状态符合规定。 |
| 3 Android与数据链跨端闭环 | 模式/规则提交、实际应用版本与动作解释；设备快照、云端、串口CSV、Room和历史回放一致保存AUTO/规则版本/事件。保留真实旧记录，不把手机保存配置当设备生效。 |
| 4 G5全应用验收（当前范围已认可） | 导航和状态一致、冷启动、网络恢复、后台/息屏、适配、连续操作、共享资源释放、历史隔离/回放禁控及性能；未测限制与整体验收结论分别记录。 |
| 5 K/M3最终交付（当前范围收口；原完整标准未达成） | 核对原项目必需能力，交付源码/APK/固件/归档工具/真实数据及配置身份；排除凭据，核实云额度/历史权益或保留限制；整套冷启动重复演示后才能登记M3。 |

现有契约虽枚举AUTO，固件实际仅接受MANUAL，AUTO会返回 `UNSUPPORTED_MODE`，配置版本仍为1。手机G4报警阈值不等于设备启停、滞回或保护参数，不能直接套用。下一次如获准推进跨端，从参数和契约关口开始，不重新做已完成的预约/场景，也不直接开放AUTO开关。

## 7. 续接必须读的文件

先读本交接第一、六节确认最新认可和“不做”范围，再按下列顺序读取。文件里的计划/提示词不替代用户请求，旧等待状态不推翻本次认可。

### 第一批：进度与用户要求

1. [D7 开发过程与阶段执行手册](<D:/数字孪生智慧大棚/第三版项目文档说明/7开发过程与阶段执行手册 V3.0.docx>)：先第三十七—四十节（预约/场景实现、证据和阶段计数）；第三十三/三十四节作G4及范围审阅历史。D7第四十一节已同步场景认可与本轮G5状态，原第三十八至四十节保留历史写入时点。查M0读第十八节，G1读第二十三/二十四节，G2读第二十七节，G3读第二十九节。
2. 用户指定的三份前端大纲必须原文遵守：[1 前端分组开发与验收大纲](D:/数字孪生智慧大棚/前端构建提示词/前端开发大纲/1前端分组开发与验收大纲.txt)、[2 首组开发启动提示词](D:/数字孪生智慧大棚/前端构建提示词/前端开发大纲/2首组开发启动提示词.txt)、[3 返修与后续分组执行提示词](D:/数字孪生智慧大棚/前端构建提示词/前端开发大纲/3返修与后续分组执行提示词.txt)。
3. [前端审美迭代](D:/数字孪生智慧大棚/前端构建提示词/前端审美迭代.txt)，以及同目录原 1–8 份页面提示词，尤其 [状态补全提示词](D:/数字孪生智慧大棚/前端构建提示词/8状态补全提示词：防止设计稿只覆盖“理想情况”.txt)。
4. 先读第二节最新场景的 `acceptance-round01.json`、`delivery-build03.json`、`source-build03-hashes.json`、两个审计JSON和 `ui-review-build03.json`，再读离线预约 `acceptance-round01.json`、`delivery-build02.json`。场景build03清单只证明该历史版本；Android现有源码身份以G5 build02清单为准；G4最终验收/128项索引作历史底座。核查实际测试版本、身份和限制，不从旧待认可状态重复开工。

### 第二批：设计、契约与资产

- [D2 项目核心目标](<D:/数字孪生智慧大棚/第三版项目文档说明/2项目核心目标 V3.0.docx>)、[D4 物理实验与模拟感知层](<D:/数字孪生智慧大棚/第三版项目文档说明/4第一层设计：物理实验与模拟感知层 V3.0.docx>)、[D5 核心三层需求](<D:/数字孪生智慧大棚/第三版项目文档说明/5核心三层深度需求设计 V3.0.docx>)、[D6 Android 需求](<D:/数字孪生智慧大棚/第三版项目文档说明/6Android应用层详细需求报告 V3.0.docx>)。涉及原项目全范围再读 D1/D3。
- [契约目录](D:/数字孪生智慧大棚/项目源码/contracts)：`record.schema.json`、`command.schema.json`、`csv-columns.json`、`runtime.json`、`onenet-mapping.json`；与实际固件和验收一起核对。`onenet-model.draft.json`、示例 JSONL 是草案/样例，不是实机证据。
- [V2 映射](D:/数字孪生智慧大棚/项目源码/twin-assets/greenhouse-v2/export/twin-map.json)；同一资产目录 `validation/android-frontend-prompt-draft.txt`、`qa-report.json`、`glb-statistics.json`、`node-mapping-validation.json`、`gltf-validator-review.json`、`reimport-validation.json`。Android 实际打包资产为 `app/src/main/assets/models/greenhouse.glb` 与 `twin-map.json`。
- [素材使用说明](D:/数字孪生智慧大棚/前端素材库/素材库使用说明.md)、`图标索引.csv`、`迁移清单.json`，及 `动效组件/说明书` 下环境/设备/导航图标三份说明。
- [硬件原理图目录](D:/数字孪生智慧大棚/04-原理图&功能图)；实际接线和已确认结果看 D7/M0 证据。

### 第三批：已有源码入口

Android 工程根为 [项目源码/android-app](D:/数字孪生智慧大棚/项目源码/android-app)，包根为 `app/src/main/java/cn/greenhouse/twin`。以下路径相对于包根：

| 需要了解的实现 | 文件 |
| --- | --- |
| 入口、导航、共享仓库 | `MainActivity.kt`、`GreenhouseApplication.kt`、`ui/home/HomeViewModel.kt` |
| 实时同步及控制身份 | `data/repository/HomeStateRepository.kt`、`data/remote/OneNetClient.kt`、`domain/ControlRequestState.kt`、`data/local/ControlJournal.kt` |
| 标准控制页面/浮层及场景 | `ui/control/DeviceControlScreen.kt`、`DeviceControlSheet.kt`、`ManualScenePanel.kt` |
| 预约/场景领域与诊断 | `domain/ControlRequestState.kt`、`SceneExecutionState.kt`、`GreenhouseTwinState.kt`；`data/repository/SyncDiagnostics.kt`；原子持久化仍在 `data/local/ControlJournal.kt` |
| 本地报警、建议、设置 | `data/monitoring/MonitorRepository.kt`、`AlarmPersistence.kt`、`domain/monitoring/MonitoringModels.kt`、`ui/monitoring/AlarmCenterScreen.kt`、`EnvironmentAdviceSheet.kt`、`ui/settings/SettingsScreen.kt`、`data/local/ConnectionStore.kt` |
| Room/CSV/时间线 | `data/local/TwinDatabase.kt`、`data/history/HistoryRepository.kt`、`HistoryRecordCodec.kt`、`CsvArchiveReader.kt`、`domain/history/HistoryTimeline.kt` |
| 数据中心及回放 | `domain/history/ReplayState.kt`、`ui/history/DataCenterScreen.kt`、`HistoryViewModel.kt`、`ReplayScreen.kt` |
| 3D 资源和映射 | `ui/twin/GreenhouseTwinScreen.kt`、`ui/scene/GreenhouseModelPreview.kt`、`GreenhouseRenderResources.kt`、`GreenhouseAssetMapping.kt`、`GreenhouseSceneTypes.kt` |

固件入口：[platformio.ini](D:/数字孪生智慧大棚/项目源码/esp32-firmware/platformio.ini)、[main.cpp](D:/数字孪生智慧大棚/项目源码/esp32-firmware/src/main.cpp)、[cloud.cpp](D:/数字孪生智慧大棚/项目源码/esp32-firmware/src/cloud.cpp)、[command_store.h](D:/数字孪生智慧大棚/项目源码/esp32-firmware/include/command_store.h)。

归档工具：[archiver.py](D:/数字孪生智慧大棚/项目源码/serial-archiver/archiver.py)。已有 Windows 排他锁、SQLite WAL/FULL、严格 DATA/LOG/schema/UTF-8、身份去重和 CSV 写盘/重建，不重写。`--send`、`--interactive` 和 `--restart-on-usb-reconnect` 涉及诊断/启动操作，不能当纯只读采集使用。

## 8. 环境、凭据与操作边界

- 系统 Windows，Shell PowerShell；项目没有 Git 仓库，不虚构分支/提交。
- Android：Gradle **8.13**、AGP **8.13.2**、Kotlin/Compose 插件 **2.0.21**、KSP **2.0.21-1.0.28**；Compose BOM **2025.03.00**；Navigation/Lifecycle **2.8.3**；Room **2.7.2**；DataStore **1.1.7**；SceneView **2.3.0**；Vico **1.16.0**。不要未经要求升级依赖。
- SDK/target **36**、Build Tools **36.0.0**、minSdk **24**，运行构建用 `D:\java-21`，源码编译目标 Java **17**；SDK `D:\Android\Sdk`，Gradle 缓存 `D:\Android\Gradle`。本轮实际使用Gradle离线构建最终G5 build02，未升级依赖。复现入口见下方命令。
- PlatformIO：`env:greenhouse`，`espressif32@7.1.3`、`esp32dev`、Arduino、C++17、串口 **115200**，`pre:configure.py` 生成配置；复用既有构建烧录流程。
- 已验收手机 **vivo V2301A / Android 14 / API34**，包名 `cn.greenhouse.twin`。ADB 位于 `D:\Android\Sdk\platform-tools\adb.exe`。2026-10-11联调用USB序列号 `10AE381EAT00625`，历史无线地址 `192.168.108.116:5555` 只作旧排查参考；两者都须重新发现。2026-10-29已重新连接该USB手机并实际联调；续接时仍须重新发现并查询当前状态。
- ESP32-WROOM-32、30Pin DevKit 类板、01Studio PyBase；OLED RIT253，0.96 英寸、128×64。上一轮端口 COM5/CP210x，USB VID10C4/PIDEA60、序列号01E55E74；三路 LED 为 Pump=X1/GPIO18、Lamp=X2/GPIO19、Fan=X3/GPIO23，各串联限流电阻。后续先检查实际端口与接线。
- OneNET 项目产品 **DigitalTwinGreenhouse**，产品 ID **28Q9sTZMG6**，设备 **ESP32-ccaa29c4f5fc**；ESP32 MAC **FC:F5:C4:29:AA:CC**。设备网络曾改为 2.4GHz 热点 `god`，最新可用性须核实。
- Android 产品凭据位置：`项目源码/android-app/onenet-api.local.json`；固件本地配置：`项目源码/esp32-firmware/cloud.local.json`、`network.local.json`。只记录路径，**密码、accessKey、Token 不写入 HANDOFF、APK、文档或公开证据**；不要打印内容。手机端已有 KeyStore 加密私有存储，复用原导入/存储逻辑。
- 不读取/操作无关手机 App；系统安装、调试或登录确需用户处理时只请求具体操作。不要重复询问已确认的视觉、规则和依赖选择。

### 复现既有构建的环境命令

以下为本轮实际构建使用的环境；续接仍按用户指定范围操作。

```powershell
Set-Location -LiteralPath 'D:\数字孪生智慧大棚\项目源码\android-app'
$env:JAVA_HOME = 'D:\java-21'
$env:GRADLE_USER_HOME = 'D:\Android\Gradle'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'
.\gradlew.bat :app:assembleDebug --offline --no-daemon --console=plain
```

## 9. 后续工作约束

1. 先读实现与证据，复用既有逻辑；迁移先 copy 再改，不能从头重写。
2. 注释中文、UTF-8，代码不含 emoji；不加 fallback/旧兼容，修正导致触发问题的场景，不过度优化。
3. 未明确要求时不新增测试脚本或专门项目说明 MD。**本 HANDOFF.md 是用户明确指定的例外**。
4. 不猜参数，不把源代码审查、构建成功、云端接受、模拟数据或用户视觉认可互相替代为验收证据。
5. 过程、证据、反馈、阶段结论和下一提示词更新 D7；D1–6 只作必要技术设计；用户三份前端大纲保持原文。
6. 恢复工作先说明当前阶段、复用哪些结果及准备完成的具体任务。当前G5 build02已安装、部分核验完成；2026-10-29已恢复推进，最新见第十二节和G5进度JSON；跨端暂保留暂停，范围澄清待回复。用户已认可场景build03，不再重复询问。

## 10. 本轮 G5 首次构建记录（安装前历史时点）

- 用户请求：阅读说明书，完成未完成任务。已阅读 HANDOFF、D7、D2 及相关大纲/设计，检查现有固件、Android、契约与已交付基线，复用已有唯一同步、控制日志、Room、回放及 3D 资源。
- 修复：`HistoryViewModel.kt` 增加唯一回放加载任务的取消及代次核对；刷新/退出后旧成功或错误均不能覆盖新会话。未选历史设备时发布真实空态，修复永久加载；进入回放时不复用正在读取或读取失败的旧窗口；前后台切换保留加载/错误展示；历史查询错误回写也先检查取消状态。原文件与版本配置均先 copy，备份哈希与场景 build03 匹配。
- 最新 APK：[智慧大棚-G5-0.5.0-round01-build02.apk](D:/数字孪生智慧大棚/验收记录/Android/G5/round-01/智慧大棚-G5-0.5.0-round01-build02.apk)，**74614845 字节**，SHA256 `712bf343b38a7d352d4fc6549ed9d985e7ceac084a0a06aeaee1789951790f29`。最终离线 Gradle 构建成功（33 秒），aapt2 确认包名/版本9/0.5.0-g5-r01/min24/target36，apksigner v2 签名核验通过。APK 与构建输出哈希一致，GLB/映射保持相同；打包条目无本地凭据文件，未读取真实密钥作字节扫描。
- 证据目录：`验收记录/Android/G5/round-01`。`baseline-audit.json` 记录初始59/59一致及改动前备份；`source-build02-hashes.json` 为最终59份源码身份；`delivery-build02.json`、`acceptance-round01.json`、`build-02.log`、`signature-build02.log` 记录构建、包身份与明确未执行项。build01为中间构建，最终新增取消检查后重新构建build02。
- 尚未完成：ADB未发现vivo V2301A，故未安装此G5 APK、未核对当前手机base.apk，未完成本版七页导航/冷启动/网络恢复/后台息屏/字体宽度/连续操作/实时历史隔离/资源释放及性能真机检查，也未取得G5最终版本用户认可。旧G2/G4/G4E真机与性能证据不冒充本版实测。COM5可见不表示本轮已做串口或物理输出验收。
- 当前 D7：[开发过程与阶段执行手册](<D:/数字孪生智慧大棚/第三版项目文档说明/7开发过程与阶段执行手册 V3.0.docx>)，133075字节、579个正文直接段落，SHA256 `4e3adbb400e6450bda869413ab1ee973367bba82df1d9e292ccff626e130451a`。本轮最终版式88页，84页与已核验基线像素一致，1/4/87/88页重新实际检查通过；证据为 `G5/round-01/layout-review/qa.json`。
- 当时继续所需（连接与安装现已完成）：用USB连接vivo V2301A、开启USB调试并允许电脑调试后，安装本最终包并继续G5当前范围验收。是否解除跨端暂停待用户澄清；如果解除，先收口缺失AUTO/设备报警参数及配置契约再实现。剩五关保持不变，G5和原K/M3未完成。

## 11. 2026-10-11 用户暂停时的历史进度

用户明确“先暂停，保留进度，我有事要出去”。已停止进一步手机操作、串口采集及验收推进，不创建自动续跑。续接快照：[runtime-progress.json](D:/数字孪生智慧大棚/验收记录/Android/G5/round-01/runtime-progress.json)。D7第41节保留安装前历史；本节为2026-10-11暂停时点，2026-10-29更新见第十二节。

- 版本：0.5.0-g5-r01/code9/build02已安装，交付APK、构建输出和手机base.apk哈希一致，最终59份源码再核对一致。COLD启动1328ms。
- 已实测：七页导航、回放真实历史与缺口、业务层禁控、退出先清实时UNKNOWN；后台8.184秒与息屏暂停，解锁恢复不自行播放。手机断网查询失败/禁控及恢复；设备120秒WiFi诊断暂停期间同boot连续采样并自行恢复。
- 控制：Pump ON APP-fa8d888a5e1f468b965ea4d4d5dba823、OFF APP-b11d6634ba52432c94a806b028727644真实云端/设备/App确认，stateVersion0→1→2；末次三路OFF。旧SCENE-f7eb8dcbe5074ad08f11e928b669a622仍PARTIAL1/2、Lamp未发，无续发。未取得新的用户LED/OLED人工观察，不将串口替代人工验收。
- 适配：实检360dp、约430.8dp、font1.3七页及全屏；密度560/font1.0/mobile_data1已恢复。转场、锁屏、无效或误命名样本不作通过依据。
- 性能：180秒有效分段约60.23fps，p95间隔16.604ms，GPU耗时未测。初始10轮缺同模式证明；稳定9轮完成，第10轮UI归属守卫中止。稳定PSS基线721526KB、峰1193918KB、第9轮1034725KB；中止后的普通竖屏静置60秒826755KB，后台642017KB。峰值较高，未证明长时无泄漏；Activity完整退出资源采样因暂停中止，未取得最终证明。performance-review.json已标记PARTIAL_PAUSED。
- 最后手机采样：device/65-home-before-activity-finish-g1-state.json，LIVE/SUCCESS/ONLINE/FRESH，boot f9a7ea13404f4877a4c8bf7a32f642d3、seq353、recordCount7538，三路OFF。只证明采样时状态，不保证当前仍在线。serial-network末次snapshot308，Ctrl+C结束并记录USER_STOP utc1791726903396；serial-live仅本任务对应进程停止，非USER_STOP。UI/内存会话97983因暂停Ctrl+C停止，无继续采集任务。

续接时重新发现ADB/COM及当前状态，复用build02与现有证据，完成以下欠项：

1. Activity真正退出后的舞台、引擎、EGL释放及内存证据；采集因用户暂停中止。
2. 历史回放快速进出、加载中换窗口、空时间窗的补充真机检查。
3. 最终Room副本与安装前7356条历史、5220条归档表达逐条对比。
4. 两套串口归档SQLite/CSV一致性和序列完整性复核。
5. 本版动效、3D与回放短录屏。
6. D7第42节过程补记与版式检查。
7. G5最终用户认可；切页高内存峰值和长时泄漏限制未收口。

跨端/AUTO保持暂停，剩五关与原K/M3未完成保持不变。不重复开发或索取已认可预约/场景的认可。用户未明确继续前不自动推进。


## 12. 2026-10-29 ESP32换板、烧录及可视化（最新）

用户旧板损坏，新板通过USB连接COM7；ESP32-D0WDQ6 rev1.0，实际MAC EC:62:60:B6:7A:38（旧板MAC不得冒充新板MAC）。WiFi换为开放的 Library-Free（实测2.4GHz）；cloud.local.json继续使用原OneNET配置。configure.py允许明确的开放网络空密码，src/main.cpp将串口设备ID统一为原OneNET逻辑设备名 ESP32-ccaa29c4f5fc，以继续关联云端与历史；设备名中旧MAC字符只表示逻辑名，不是新板MAC。原源码已先备份到验收目录。

新板构建 SUCCESS（39.49秒），COM7烧录 SUCCESS（34.48秒），四个写入段哈希校验通过。第一次运行boot 0526195a8b0c40f08f64c64be114397c；实测连接Library-Free、SNTP同步、TLS/MQTT连接及CLOUD_POST_ACCEPTED，串口有效记录均使用原逻辑设备名，云端发布成功不等同手机链路验证。首轮三路OFF，记录有温湿度传感器值、光照实验值和土壤模拟值。新板不继承旧板NVS命令历史。

VS Code使用D盘Core打开固件工程，PlatformIO Home已实际显示 Espressif 32 7.1.3，工程任务显示 Build/Upload/Monitor；真实上传成功日志在窗口可见。Windows用户环境变量 PLATFORMIO_CORE_DIR 已设 D:\PlatformIO。串口仅允许一个程序占用，归档结束后才可打开Monitor。

用户断电接好外设并重新通电，COM7重新出现但15秒无串口数据。已确认USB身份后复用一次150ms RTS软件复位，捕获BOOT、新boot a1f5cb97162be1baf71a03532bce941f、WiFi、SNTP、TLS/MQTT及云端POST成功；启动原始字节保存在 reset-raw.bin，未作为实时接收时间重导入。串口本地诊断三路各ON/OFF，状态版本0→6，最后真实snapshot8三路OFF、WiFi/云端在线；当时用户LED/OLED目视反馈尚未收到，后来已完成，见下方追加记录。本次断电重接需复位的根因尚未确认，不能标记自动重启已通过。当时手机未发现，后来已连接并完成水泵闭环，见下方追加记录。完整进度、哈希和证据路径见 [replacement-progress.json](D:/数字孪生智慧大棚/验收记录/M0/board-replacement-20261029/replacement-progress.json) 及同目录 build.log、upload.log、serial。D7已追加换板与G5第42节及用户调整验收标准；最终93页，SHA256 55bcf9bbca885a447d7f0544ba1d7e7c7ea343ca910657f0c7be2ec13abd9674，新增页已做必要版式检查；G5及原项目剩余关口不因换板烧录成功而完成。AUTO及未授权跨端能力仍暂停。

用户随后回复已按下并松开复位键，具体按键时刻未核实；后续实时串口采到新boot f6cf5add99abad5aadcd06f7faa0758b，不可仅凭boot变化断定因果。按用户要求重新演示三路各约5秒，六次LOCAL_ACTUATOR_SET事件记录ON/OFF，最后快照三路OFF、WiFi与云端在线；用户随后说明“三灯都没亮”，OLED照片显示WIFI ON/CLOUD ON、P0 L0 F0；照片未包括三盏LED与完整接线，不能判定硬件成因，不能登记LED通过。OLED照片已归档 oled-observation-user.jpg。原始归档serial-manual-reset，采集已USER_STOP并释放串口。

2026-10-29追加排障：用户确认三路均为X1/X2/X3外接LED，之前三路演示均未亮；单独约15秒pump ON/OFF实测串口stateVersion 6→7→8，用户确认OLED的P变1但X1未亮，最后快照三路OFF；OLED与状态更新可见，不等于GPIO实测。旧板为01Studio特定排针布局，相同WROOM-32模组不保证新开发板针序相同，需断电后核对新板双排丝印、底板X1–X3与LED/电阻/GND实物，不猜原因。证据serial-pump-witness。

身份契约注意：历史D5第十一节写deviceId取芯片MAC构造，而本次为沿用OneNET逻辑设备名将串口deviceId改为CLOUD_DEVICE，现有语义与该文档不一致。此为已知契约语义偏差，仍需正式对齐；新板CSV与手机后续已完成实测，不能据此称无契约影响；新MAC记录在换板证据中。

用户表示“再试一次，我可能接线有问题”，随后三路各约7秒重试，本地六个ON/OFF事件和末次三路OFF快照存于serial-led-retry；用户回复“三盏都依次亮了”、结束后“都已熄灭”，并确认“调整过接线”；此前三灯不亮的确切原因未定位。采集器已USER_STOP。

2026-10-29 Android新板闭环：vivo V2301A实际连接并启动0.5.0-g5-r01，显示LIVE/SUCCESS/ONLINE/FRESH且boot一致。手机水泵ON APP-5f6a53a08fa040648b54d15170ecf550 与OFF APP-a2d3c5e2141a4f87902c02ec2301b6e8，设备分别REMOTE_ACTUATOR_SET及COMMAND_RESULT EXECUTED，stateVersion 16→17→18，手机关联确认；用户目视回复“没问题，后面两个灯就不用测了，浪费时间”，按用户要求不测手机另两路。末次三路OFF。另导入新板真实串口CSV预览25条/重复23/问题0，实际新增2、重复跳过23、未导入0，25条归档表示全部入库；导入后与旧基线逐行核对7356原历史与5220原归档表示无缺失、无修改。seq97/100手机与串口各26项核心字段一致；更多证据见 android 和serial-android-witness。G5其他欠项、断电自启和D5身份语义对齐未因此通过。


2026-10-29继续G5：目标手机当前0.5.0-g5-r01/code9的base.apk SHA256再次核对为712bf343b38a7d352d4fc6549ed9d985e7ceac084a0a06aeaee1789951790f29，与交付包相同。空历史时段0快照0事件，回放显示未知/禁控，三次进入与退出返回LIVE；未模拟加载中切窗、晚到失败和无历史设备。应用Activity退出时，独立pid11967日志记录3D场景、完整舞台、引擎和EGL上下文释放；此前稳定全屏9轮峰值1193918KB，长时泄漏和GPU耗时仍未证明。G5原两套串口归档已提交DB/CSV一致、已保存序列连续，强停窗口与未采集范围不作推断。35.045秒录屏 g5-device-review-20261029.mp4 展示3D旋转、首页和控制浮层，未录真实控制开关，不作为帧率测量。其后用户已认可当前G5版本；原K/M3完整标准不冒认通过，当前范围的交付状态和风险看G5/runtime-progress.json。


## 13. 2026-10-29 用户调整验收与迭代标准（最新）

用户要求重新审视过苛刻验收条件：功能迭代的置信度没有70%以上就放弃。执行口径：只有现有证据能够合理支持成功把握达到70%、且问题与验证方式清楚时才实施迭代；不能凭空给出精确概率。放弃的是把握不足的本次迭代或低收益测试，不删除已有功能，也不把未验证标成通过。

当前G5已具备实用核心链路证据：目标APK安装身份、七页与核心异常状态、3D、真实数据和CSV、历史隔离/回放禁控、手机水泵与OLED/LED闭环。按用户要求，手机另两路不重复测试；无实际故障证据的极端回放竞态注入、长时内存/GPU专项及无明确根因的3D优化不再列为本轮必做验收。保留高内存峰值、断电重接曾需复位、D5设备ID与新板逻辑设备名语义差异等已知事实；不冒称已解决，只有出现明确影响或有足够证据的可行方案再针对性处理。

当前范围可供用户审阅并正常使用，不因未执行高成本专项而反复延长本轮；用户随后明确回复“认可当前 G5 版本”；该认可仅对应当前已完成范围。AUTO等跨端能力及原K/M3全项目未因收尾标准调整而自动完成。证据和详细边界见G5/runtime-progress.json和新板replacement-progress.json；此前章节保留历史时点。

D7最新版本记录G5认可、跨端不做与当前范围交付，94页、609段，SHA256 405e7bc7a858ca286c70a9a0d99f4910c814faff82a9682d022564ad6602477d；必要版式检查见G5/round-01/layout-review-delivery/qa.json。


## 14. 2026-10-29 用户认可 G5 与收窄范围（最新）

用户明确回复“认可当前 G5 版本”，认可已安装的 0.5.0-g5-r01、versionCode 9、build02 和当前已完成范围；遵循第十三节调整后的实用验收标准。已在G5/acceptance-round01.json、runtime-progress.json及delivery-build02.json记录原话。此认可不把未测试写成通过，也不表示原项目中未实现的跨端功能已经完成。

用户对AUTO、设备规则和本地设备报警的跨端能力明确回复“不做跨端能力了，继续（还有活的话）”。因此这些能力不纳入本次工作，而不是继续等待实施授权。原五个后续关口中G5当前范围已认可，原计划余四关：三项跨端关不纳入当前工作范围，已认可范围的实际交付资料已整理完成；原K/M3中包含被排除能力的完整标准不能冒称已达成。保留D1–D6原需求和已知风险作为历史，不静默改写契约。

执行原则：只做具体必要、合理成功把握达到70%以上的工作，不重复高成本测试，不重复用户明确跳过的两路手机控制。优先核查交付资产、源码、数据与本地运行入口，防止将凭据、生成头或含凭据的固件二进制当成可公开分发物；已有文件优先复用，不重复造轮子。


## 15. 2026-10-29 当前范围交付已就绪（最新）

交付包：[智慧大棚-当前范围交付.zip](D:/数字孪生智慧大棚/output/当前范围交付-20261029/智慧大棚-当前范围交付.zip)，101899052字节、128项，SHA256 f92e66c66eddfc705d6d7aa07efcae4cd5051edd65bfc17cdea90c15197038fc；ZIP完整性与逐项哈希已核验。包内有用户认可的debug APK、Android/ESP32源码及构建文件、契约、串口归档工具、空值本地配置示例和新板真实CSV。D7副本与机器可读入口见同目录 [交付清单.json](D:/数字孪生智慧大棚/output/当前范围交付-20261029/交付清单.json)。

包内排除本机WiFi/云端私有配置、Android密钥配置、generated_config.h、含密钥的已配置固件bin/ELF和构建缓存；对本机两项已知密钥原文及可解码字节，在源码和APK各条目做过定向匹配无命中，不宣称全面安全审计。重新构建固件需按示例自建并填写本地网络和云配置；脱敏包未独立构建。真实CSV包含设备身份与时间，按实际需要使用。原工作区保留可直接运行的本机配置和已烧录设备状态。

用户已认可G5当前范围，明确不做AUTO、设备规则、设备本地报警等跨端能力。当前范围交付资料已就绪，不再以极端测试或低于70%把握的猜测性迭代拖延。原项目完整跨端需求未实现，不冒称原K/M3全部通过；USB重连一次需复位、D5设备ID定义差异和高内存峰值保留为限制。


## 16. 2026-11-02 GitHub 首次私有上传（历史，当前公开状态见第十七节）

用户要求把项目相关文件上传 GitHub。已创建私有仓库 [digital-twin-greenhouse](https://github.com/gjhghjuh/digital-twin-greenhouse)，默认分支 main；初始源码提交 47827883a22202ee8fb3c152f48e73de00594296。仓库在线可浏览 Android/ESP32 源码、3D 模型和图标、契约、串口归档工具、真实 CSV、七份 V3.0 项目文档、原 HANDOFF 与交付清单，共139个文件。沿用已脱敏交付包，没有上传本机网络/OneNET凭据、生成配置头、含凭据固件编译产物或构建缓存；公开CA证书 certificate.pem 已纳入。

下载版本：[v0.5.0-g5-r01](https://github.com/gjhghjuh/digital-twin-greenhouse/releases/tag/v0.5.0-g5-r01)。附件为已认可的debug APK、完整脱敏交付ZIP、D7副本、交付清单、35秒实机录屏；5项附件均已上传，GitHub返回的SHA256逐项与本地一致。文件使用英文下载名，内容与本地原件一致；当前范围与已知限制保持不变。私有仓库与Release需使用有权限的GitHub账号访问。

本地可继续提交的脱敏Git工作副本位于 output/github-upload-20261102/repository，与保留真实私有配置的原工作区分开。后续上传应继续按白名单同步，不得将整个原工作区直接加入Git。上传文件清单、校验值、远端提交与附件地址见 output/github-upload-20261102/upload-progress.json。Chrome附件接口因扩展未允许文件URL而不可用，最终复用本机现有GitHub凭据通过Git和GitHub API完成；未更改浏览器安全设置、未创建新访问密钥。


## 17. 2026-11-02 GitHub 公开与 README（最新）

用户明确要求“把仓库改成公开的，方便别人查看，并且要有README”。已将 [digital-twin-greenhouse](https://github.com/gjhghjuh/digital-twin-greenhouse) 从私有改为公开；第十六节的私有状态仅为首次上传时的历史。未携带Authorization的请求已核对仓库public标记、README正文和全部5个Release附件的下载入口，均可访问；附件大小与已验证的服务端SHA256仍与本地原件一致。

仓库根目录已增加README.md及三张实际G5手机截图，内容包括功能/模拟范围、界面预览、架构图、目录、APK与录屏入口、Android构建、独立OneNET接入前提、PlatformIO可视化与烧录、GPIO接线、串口归档及已知限制。截图裁去系统状态栏，仅展示项目界面。README明确现有APK的产品/设备身份固定；使用自己的云设备须修改对应源码常量和映射后构建，没有公开共享密钥。

本轮仅修改仓库展示与交付状态，未修改功能源码、固件或已认可APK。新README及截图不属于原交付ZIP清单，原Release版本和原件哈希保持不变。公开前复核已提交历史和5个附件：未发现已知密钥或明确实际令牌/私钥，源码动态签名拼接模板与普通设备标识不冒充泄密。没有新建访问密钥。

可继续提交的本地Git工作副本：output/github-upload-20261102/repository；README位于该工作副本根目录。公开核验记录：output/github-upload-20261102/public-readme-verification.json；完整上传索引继续使用同目录upload-progress.json。
