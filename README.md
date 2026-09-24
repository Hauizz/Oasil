# Oasil 学习平台

一个基于 **Spring Boot + Spring AI** 的本地学习助手，包含智能问答、四六级模拟考试（带自动阅卷与逐题解析）、文件仓库、答题模式和考试记录。

所有数据都落在**本地 SQLite 与本地磁盘**，不依赖任何云端数据库。

## 功能一览

| 模块 | 说明 |
| --- | --- |
| 💬 智能问答 | 接入 DeepSeek，角色设定写在代码里可自由修改；带会话记忆，追问能接上文；问答记录落库 |
| ✍ 四六级模拟考试 | 选级别 → 选年月 → 选套 → 开考；严格分阶段计时；交卷后按解析文档自动阅卷 |
| 📁 文件仓库 | 上传 / 预览 / 下载 / 删除，文件存本地磁盘 + SQLite 元数据 |
| 🔎 答题模式 | 从文件仓库里的 Word / PDF 抽取题目，逐题作答 |
| 📊 考试记录 | 历次模考成绩与逐题回顾（对错、解析、范文、译文对比） |

## 技术栈

| 组件 | 版本 / 说明 |
| --- | --- |
| Java | 17 |
| Spring Boot | 4.1.1（spring-boot-starter-web） |
| Spring AI | 2.0.1（spring-ai-starter-model-openai，对接 DeepSeek） |
| Apache POI | 5.2.5（解析 docx 真题与解析） |
| SQLite | sqlite-jdbc 3.44.1.0（原生 JDBC，无 ORM） |
| 前端 | 原生 HTML + CSS + ES Module，无构建步骤 |
| PDF | 自研 `PdfTextExtractor`（纯字符串解析，无第三方 PDF 库） |

## 模拟考试流程

1. **选卷**：四级 / 六级 → 年份月份 → 第几套
2. **写作（30 分钟）**：只显示作文题目与输入框，看不到后面题目；倒计时结束自动进入听力
3. **听力**：自动播放听力音频，Section A / B / C 可自由切换；本阶段结束后，作文与听力答案锁定不可修改
4. **阅读 + 翻译（70 分钟）**：左侧文章 / 题目、右侧作答区，底部四个按钮自由切换（阅读三篇 + 翻译）
5. **收卷**：时间到自动收卷 → 依据解析文档自动阅卷 → 进入逐题回顾

补充说明：

- 若某一部分与其它套重复（即试卷里没给），该阶段会**整段跳过**
- 写作与翻译按**单词数**统计（非字符数）
- 回顾页：写作区左侧为题目 / 审题 / 范文 / 范文翻译，右侧为本人作文；阅读区左侧为逐段原文 + 译文，右侧为题目与逐题解析

## 快速开始

### 环境要求

- **JDK 17+**
- **Maven 3.9+**（也可以直接用项目自带的 `mvnw`）
- **DeepSeek API Key**（只使用文件仓库 / 考试记录时可以不配）

### 方式一：一键启动（Windows 推荐）

双击项目根目录的 **`启动网站.bat`**，脚本会自动：

1. 查找 Java 17+（系统 `JAVA_HOME` 指向低版本也没关系，脚本会自己找）
2. 检测到源码比程序包新时自动重新打包
3. 启动服务并等待就绪
4. 自动打开默认浏览器 → <http://localhost:8080/>

服务在前台运行，该窗口即服务控制台。**停止服务**：关闭窗口 / `Ctrl+C` / 双击 `停止网站.bat`。

```powershell
.\启动网站.bat                # 默认 8080 端口，自动开浏览器
.\启动网站.bat -Port 9000     # 指定端口
.\启动网站.bat -NoBrowser     # 只启动服务
.\停止网站.bat -Port 9000     # 停止指定端口上的服务
```

### 方式二：命令行

```bash
cd oasilplatform
mvn -DskipTests package
java -jar target/spring-ai-chat-demo-0.0.1-SNAPSHOT.jar
```

### 配置 DeepSeek API Key

`application.yml` 中写的是 `${deepseek_api_key}`，二选一即可：

1. 设置环境变量 `deepseek_api_key`
2. 在项目根目录新建 `deepseek-key.txt`，把 key 粘进去（启动脚本会自动读取并注入）

> `deepseek-key.txt` 不会进版本库，**请勿提交**。未配置 key 时其余功能正常，只有智能问答会调用失败。

## 页面地址

| 页面 | 地址 |
| --- | --- |
| 首页菜单 | <http://localhost:8080/> （会 302 跳转到 `/menu.html`） |
| 答题模式 | <http://localhost:8080/qa.html> |
| 四六级模拟考试 | <http://localhost:8080/test.html> |
| 智能问答 | <http://localhost:8080/index.html> |
| 文件仓库 | <http://localhost:8080/repository.html> |
| 考试记录 | <http://localhost:8080/logs.html> |

## 四六级试题材料（重要）

> 出于版权原因，本仓库**不包含**任何四六级真题、听力音频与解析文档，需要自行准备。

把材料放进 `data/repository`（可用 `app.exam.dir` 指定其它绝对路径），目录结构示例：

```text
data/repository/
├─ CET-4/
│  └─ 2025年12月四级真题+听力+答案/
│     ├─ 2025.12四级真题第1套.docx     # 题面（Word 优先，PDF 兜底）
│     ├─ 2025.12四级解析第1套.docx     # 解析（自动阅卷与逐题解析依赖它）
│     └─ 2025.12四级听力第1套.mp3      # 听力音频
└─ CET-6/
   └─ ...
```

识别规则：

- **真题**：文件名含「真题」或「原题」，且不含「解析 / 答案 / 详解」
- **解析**：文件名含「解析 / 答案 / 详解」
- 年份、月份、套号从文件名解析
- `app.exam.dir` 留空时，会从工作目录与 jar 所在目录**逐级向上**查找 `<上级>/data/repository`，所以把仓库根放在项目上一层也能识别
- 建议试题文件使用.docx，识别题型会比pdf好

## 目录结构

```text
oasilplatform/
├─ src/main/java/com/example/ai/
│  ├─ AIapplication.java                 # 启动类（@SpringBootApplication）
│  ├─ controller/                        # 页面与 REST 接口
│  └─ service/                           # 问答、试卷解析、阅卷、仓库、日志等
├─ src/main/resources/
│  ├─ application.yml                    # 全部配置
│  └─ static/                            # 前端页面（html / css / js，无构建）
├─ scripts/
│  ├─ start-site.ps1                     # 一键启动逻辑
│  └─ stop-site.ps1                      # 停止逻辑
├─ 启动网站.bat / 停止网站.bat
├─ mvnw / mvnw.cmd / pom.xml
└─ 本地打开说明.md
```

## 数据存储

运行时会在工作目录下自动创建（均已被 `.gitignore` 排除，注意不要提交）：

```text
data/
├─ chat_logs.db       # 聊天日志
├─ repository.db      # 文件仓库元数据
├─ exam_records.db    # 考试记录
└─ repository/        # 上传的文件 + 四六级试题材料
```

## 免责声明

四六级真题、听力音频与解析文档的版权归各自权利人所有。本项目仅提供解析与答题的技术实现，**不提供也不分发任何试题材料**，请自行准备并仅用于个人学习，勿用于商业用途。
