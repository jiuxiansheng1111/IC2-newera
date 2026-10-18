# 手机音乐解码与专服侧加载审阅 — 2026-10-18

## 当前实现和专服边界

静态审阅 `build.gradle`、`src/main/java/com/ic2newera/media/MediaPackets.java`、`MediaServer.java`、`src/main/java/com/ic2newera/client/media/MediaClient.java`、`MediaAudioPlayer.java` 与 `MusicController.java`。项目依赖中没有 MP3 decoder；只读扫描本机 Forge/Maven 缓存 131 个 JAR，没有发现 JLayer、MP3SPI、Tritonus 或相应 `javazoom`/MP3 decoder 类。依赖块目前也没有 `mavenCentral()`。

当前音乐协议限定 `/music/prepare` 回应 `format=ogg_vorbis`，服务端按 `audio/ogg` 缓存并校验 OggS，客户端只接受 OGG/WAV，而音乐播放器入口只放行 OGG。语音是 `audio/wav`。`MediaServer` 仅下载、暂存和转发音频字节，没有解码音频；`MediaAudioPlayer` 在 `com.ic2newera.client.media` 内，调用 Minecraft 客户端 `OggAudioStream` 解码 OGG、用 Java Sound 读取 WAV。因此专服不需加载 `OggAudioStream`，而玩家客户端必须有 OGG decoder。

音频相关包位于 `client` 包；普通 `MediaPackets.Reply.handle` 通过 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)` 调用客户端接收器，与 Forge 1.19 的 S2C 包处理示例一致。`MediaServer` 是公共侧事件订阅类，没有引用 `net.minecraft.client` 或播放器类。静态源码没有发现音乐播放器类型被专服公共入口直接初始化。边界提醒：`integration.llm.InstalledModKnowledge` 虽不在 `client` 包，直接导入 `net.minecraft.client.Minecraft`，但本次检索发现它只从客户端 UI 使用的 `LLMChatClient` 调用；这不是音乐路径，且本次静态检查不能代替专服启动日志。

## MP3 选择

Java 17 的 `AudioSystem` 通过已安装 `javax.sound.sampled.spi` 服务提供者读取文件格式。Oracle Java Sound 文档明确把 MP3 作为 API 未明列、可能由实现或第三方提供者支持的格式。因此，目标 Java 17 的 `AudioSystem.getAudioInputStream(file)` 不能被当成可靠 MP3 解码器；当前项目没有声明/捆绑 MP3 SPI，操作系统安装了编码器也不等于 Java SPI 可见。

若公开音源返回 MP3，建议用小型直接解码库 JLayer `javazoom:jlayer:1.0.1`，每次解一帧到 PCM16，再走现有 `SourceDataLine`，避免完整解码到内存或依赖 Python/FFmpeg。Maven Central 的 POM 标注 LGPL；其源文件版权头写明 GNU Library General Public License version 2 或更高版本。应在分发包保留 LGPL-2.0-or-later 文本与 JLayer 版权/来源说明，不改动库源码；若修改库本身，需同时提供对应修改源码。

JLayer 1.0.1 公共接口可按以下帧循环使用：`new Bitstream(InputStream)`，`readFrame()` 得 `Header`（EOF 返回 null），`new Decoder().decodeFrame(header, bitstream)` 得 `Obuffer`，具体结果是 `SampleBuffer`；从 `getBuffer()`/`getBufferLength()` 取得交错 16-bit PCM sample，读取 `Decoder.getOutputFrequency()` 和 `getOutputChannels()` 设置目标音频格式，每帧后 `closeFrame()`，finally 中 `Bitstream.close()` 并关闭输入流。该 API 可以分帧输出，不需要 `Player` 默认音频设备或整首音频缓冲。需要单独处理/限制 ID3 前缀、坏帧/EOF、采样率/声道上限、PCM 帧字节计数和取消清理；沿用现有最大时长和总解码字节上限。

## ForgeGradle 5.1 / Jar-in-Jar 配置建议

此工程使用 ForgeGradle `[5.1,5.2)`，ForgeGradle 官方 FG 5.x 依赖指南支持 Forge 1.19+ Jar-in-Jar，说明了开发运行时普通库 classpath 与打包后的 `META-INF/jarjar/metadata.json` 是两个问题。最小配置模式如下（仅为审阅建议，本次未改 Gradle、未构建）：

```groovy
repositories {
    mavenCentral()
    // 保留现有 Forge/CurseMaven 配置
}

configurations {
    library
    implementation.extendsFrom library
}

minecraft.runs.all {
    lazyToken('minecraft_classpath') {
        configurations.library.copyRecursive().resolve()
                .collect { it.absolutePath }.join(File.pathSeparator)
    }
}

jarJar.enable()

dependencies {
    library 'javazoom:jlayer:1.0.1' // 编译与 ForgeGradle 开发运行 classpath
    jarJar(group: 'javazoom', name: 'jlayer', version: '[1.0.1,1.0.2)')
}
```

`jarJar` 的版本范围约束要与实际包含的 JAR 一致；此范围在目前仅有 1.0.1 时选定该发行版。集成后仍需由主任务做 clean/offline 环境编译、成品 JAR 的 `META-INF/jarjar/metadata.json` 和内嵌 JAR 审核，以及专服/客户端实机验证；本审阅未执行这些步骤。不要仅添加 `implementation` 后就认为正式 Forge 包会带上依赖，因为 ForgeGradle 文档指出普通库默认不会随 mod JAR 发出。

接受 MP3 MIME 后还要贯通协议边界：`MediaServer.perform` 的 `ogg_vorbis` 检查和 `download` 的 OggS 魔数判断、`MediaClient.receive` 的 MIME/扩展名白名单、`MusicController.play` 的 OGG 独占检查、`MediaAudioPlayer` 的 MIME dispatch。否则即使 JLayer 已打包，当前音乐路线仍会在前置格式校验拒绝 MP3。

## 依据

- 本地源码：`build.gradle`；`src/main/java/com/ic2newera/media/MediaPackets.java` 与 `MediaServer.java`；`src/main/java/com/ic2newera/client/media/MediaClient.java`、`MediaAudioPlayer.java`、`MusicController.java`。
- Maven Central 的 [JLayer 1.0.1 坐标与 POM 元数据](https://central.sonatype.com/artifact/javazoom/jlayer)；[1.0.1 Javadoc Decoder](https://javadoc.io/static/javazoom/jlayer/1.0.1/javazoom/jl/decoder/Decoder.html)、[Bitstream](https://javadoc.io/static/javazoom/jlayer/1.0.1/javazoom/jl/decoder/Bitstream.html)、[SampleBuffer](https://javadoc.io/static/javazoom/jlayer/1.0.1/javazoom/jl/decoder/SampleBuffer.html)。
- Java 17 [Java Sound SPI 概览](https://docs.oracle.com/javase/tutorial/sound/sampled-overview.html)与[服务提供者架构](https://docs.oracle.com/javase/tutorial/sound/SPI-intro.html)。
- MinecraftForge [ForgeGradle 5.x Dependencies / Jar-in-Jar](https://github.com/MinecraftForge/ForgeGradle/wiki/Dependencies/26600edcf3e73d0b20c5eb7e848300eb52954714)；Forge 1.19.2 [mod 结构与 client/common 子包说明](https://docs.minecraftforge.net/en/1.19.2/gettingstarted/structuring/)。

本记录是源码、依赖缓存、API 与构建配置的静态审阅；没有修改生产代码或 Gradle 文件，没有构建、测试、启动/停止 Minecraft，没有检查密钥。
