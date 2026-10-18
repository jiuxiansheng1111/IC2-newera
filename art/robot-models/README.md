# 当前机器人可编辑模型

`current/`保存八个当前运行模型对应的最终Blockbench工程，逐一匹配`art/sources/export_robot_runtime_models.py`中的批准SHA256。贴图已嵌入工程，不依赖已退役草稿或本机图片路径。

当前导出入口为`art/sources/export_robot_runtime_models.py`，直接读取`current/<角色ID>.bbmodel`，输出项目运行模型/贴图。早期R1～R15、V6～V28和头部/体型中间导出属于历史研究，不作为当前游戏模型输入。最新已构建JAR及运行资源不因工程整理而改变。
