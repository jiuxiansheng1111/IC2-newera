# 2026-10-24：cmudict 本地搜索路径补齐

## 原因

目标实例语音审计的 `LookupError` 实际请求 `corpora/cmudict`。worker 的搜索目录包含 `C:\Users\罗斌\Desktop\.minecraft\nltk_data`；此前可用的解压资源在 `C:\Users\罗斌\AppData\Roaming\nltk_data\corpora\cmudict`，不在本轮 worker 搜索目录中。

## 本地资源

仅复制本机已有的两个文件，没有联网下载，也没有更改 `NLTK_DATA`、模型配置或语音程序：

- 源：`C:\Users\罗斌\AppData\Roaming\nltk_data\corpora\cmudict\cmudict`；目标：`C:\Users\罗斌\Desktop\.minecraft\nltk_data\corpora\cmudict\cmudict`；3,820,830 字节；SHA-256 `CAD209C39EB87677D64E93D97F8EED10B7E6F9BDD42DE8E7CA8EFC8E17D62E8A`。
- 源：`C:\Users\罗斌\AppData\Roaming\nltk_data\corpora\cmudict\README`；目标：`C:\Users\罗斌\Desktop\.minecraft\nltk_data\corpora\cmudict\README`；3,808 字节；SHA-256 `B0556BE7A2B12BEA6A667277B75864F802DD4854480657CE298C62E6897E766B`。

## 检查

- 使用目标实例语音配置选择的 TTS Python、第一方 worker 环境白名单（不传 `NLTK_DATA`）、相同 GPT 根目录 cwd 做静态检查。
- NLTK 3.10.3 的 `nltk.data.find('corpora/cmudict')` 成功，返回 `C:\Users\罗斌\Desktop\.minecraft\nltk_data\corpora\cmudict`。
- 复制后源与目标两文件的字节数及 SHA-256 完全相同。
- 未运行 TTS、未做语音推理，未操作桌面、游戏进程或存档；静态查找不代表实际语音播放已验收。

## 本轮真实语音结果

2026-10-24 12:49（上海时间），目标实例本轮 anon 请求的 `last-request.json` 已更新，记录：

- 状态 `generated`；总耗时 49.969 秒；基础 TTS 与最终音频缓存均未命中。
- TTS：23.718 秒，worker 正常退出，exit code 0，末阶段 `synthesis_complete`。
- RVC：25.687 秒，worker 正常退出，exit code 0，末阶段 `rvc_model_complete`。
- PCM：0.485 秒，worker 正常退出，exit code 0。
- 生成音频 204,702 字节，时长 2.32 秒。

目标实例 `logs/latest.log` 的第一方事件记录 anon 播放开始于 12:49:33.532，完成于 12:49:36.226；播放增益为 1.0。此证据确认客户端播放流程完成，不确认用户扬声器实际可听；扬声器听感仍待用户异步答复。本记录未包含语音文本，也未触发新请求或运行测试。

## 39两句回复的中英文续测

中文实际GUI发送“请回家”，显示「我沿着路回家啦。等你回来，我们再慢慢聊。」；13:34:56服务生成481650字节、5.46秒音频，耗时46.875秒，TTS/RVC/PCM均正常exit0。客户端13:34:56.772开始、13:35:02.425完成播放。审计不保存文本，逐字关联取自GUI及时序/角色段号。

英文GUI输入go home，显示「I'm heading home on foot. We can catch up when you're back.」；首次13:37:32审计unavailable，TTS exit1、WorkerFailure/frontend_error，没有本次播放事件。最小根因是NLTK averaged_perceptron_tagger_eng缺失；worker搜索路径不含实际Roaming目录。这次不是再次缺cmudict，也没有本轮内存保护触发证据。

本机Roaming/nltk_data/taggers已存在对应英文资源，仅复制其三份JSON到.minecraft/nltk_data/taggers/averaged_perceptron_tagger_eng；目标原先不存在，复制后各自哈希一致。classes.json285字节，SHA256 D152FFA69D45B8357276341A3D592C6C025911D52EC0D9068D4F382560DD48B6；tagdict.json25788字节，4C713731CB06727736962BC8CD94AD919217A040E76691059AF99BC0C2C9C246；weights.json5677744字节，789E8E35F6FAC656D9B7ECCEA29BA4E8A137CBB5B525DD31F39E4291A9E80832。未联网下载、未改模型/保护阈值、未上传第三方语言资源。已重新从真实游戏GUI提交同一英文指令，结果待核对。

英文补齐tagger后，游戏GUI重新发送同一go home已成功：13:43:57服务生成389922字节/4.42秒，耗时44.328秒，三个worker均exit0；客户端13:43:57.347开始、13:44:01.923完成。中日英各一条两句回复均有真实游戏触发、合成和客户端完整播放证据；不冒称用户又逐一听验或LLM语义理解通过。回复语言已恢复日语。
