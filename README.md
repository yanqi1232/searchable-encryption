# Searchable Encryption (Java)

这是一个面向教学与实验的“可搜索加密”桌面系统。你可以把它理解成一个小型加密网盘：

1. 用户在客户端上传文本或文件。
2. 客户端先在本地加密正文和关键词。
3. 服务端只保存密文和可搜索索引。
4. 用户搜索关键词时，客户端生成一个查询令牌，服务端用它匹配密文索引。
5. 命中文档下载回客户端后，再由客户端本地解密。

项目使用 Java Swing + TLS Socket + MySQL 实现，适合用来学习“桌面 UI、客户端/服务端通信、数据库、加密存储、关键词检索”如何串成一个完整系统。

## 这个项目解决什么问题

普通系统搜索文档时，服务端通常能看到明文文件和明文关键词。这个项目演示的是另一种思路：

- 文件正文在客户端加密，服务端保存的是加密后的字节。
- 关键词也不直接交给服务端，而是转换成可搜索密文。
- 搜索时客户端发送 trapdoor，也就是“本次查询用的搜索令牌”。
- 服务端可以判断哪些密文关键词匹配，但不直接知道用户搜索的明文关键词。
- 下载后只有客户端持有本地密钥，因此解密也发生在客户端。

> 说明：这是教学和实验项目，重点是理解整体链路。当前实现不建议直接当生产级安全系统使用。

## 功能总览

### 用户与会话

- 支持注册、登录、退出登录。
- 服务端保存密码哈希和盐，不保存明文密码。
- 登录成功后创建会话，后续请求会检查当前连接是否已经登录。

### 文档上传

- 支持直接输入纯文本并上传。
- 支持导入单个或多个文件。
- 支持导入文件夹，程序会递归收集其中的普通文件并批量上传。
- 上传前会自动生成文档 ID。
- 可以填写描述信息，描述会参与关键词提取，并以加密元数据形式保存。
- 对文本、JSON、PDF、Word 文档会尝试抽取正文关键词。
- 图片、音视频、二进制文件也可以上传，但通常只依赖文件名和描述进行搜索。

### 关键词搜索

- 支持输入关键词搜索文档。
- 搜索词会在客户端转换为 trapdoor 后再发给服务端。
- 服务端在 `keyword_index` 表中匹配密文索引。
- 客户端展示命中文档的文件名、类型、大小、描述和预览信息。
- 图片搜索结果支持缩略图预览，点击可查看大图。
- 文本文档搜索结果会尝试展示解密后的内容片段。
- 除密文关键词匹配外，客户端还会用文档 ID 和文件名做兜底模糊匹配，提升可用性。

### 文档管理

- 支持刷新文档列表。
- 支持单个或批量下载。
- 下载后在客户端本地解密并保存到用户选择的位置。
- 支持单个或批量删除，删除文档时会同步清理关键词索引。
- 支持重建索引，适合修复旧数据或重新生成关键词密文。

### 桌面客户端体验

- 使用 Swing 和 FlatLaf 构建桌面界面。
- 上传、搜索、下载、删除等耗时任务都在后台执行，避免界面卡死。
- Busy 状态管理会在任务运行时禁用相关按钮，减少重复点击造成的问题。
- 状态栏和进度条会展示当前任务进度。
- UI 会根据窗口大小动态缩放字体、间距和按钮边距。

## 新手先看这张图

```text
┌──────────────┐        TLS Socket        ┌──────────────┐        JDBC        ┌──────────────┐
│ Swing 客户端 │  ─────────────────────>  │ Java 服务端  │  ───────────────>  │ MySQL 数据库 │
│              │  <─────────────────────  │              │  <───────────────  │              │
└──────────────┘                          └──────────────┘                    └──────────────┘
       │                                         │                                     │
       │                                         │                                     │
       ├─ 本地生成/读取用户密钥                  ├─ 校验登录会话                        ├─ users
       ├─ 加密正文                               ├─ 分发请求类型                        ├─ documents
       ├─ 加密关键词索引                         ├─ 调用 Repository                    └─ keyword_index
       └─ 下载后本地解密                         └─ 返回统一响应
```

最重要的一点：服务端负责保存和匹配，客户端负责明文处理和解密。

## 技术栈

- 语言与构建：Java 17, Maven
- 客户端：Java Swing, FlatLaf
- 网络通信：TLS Socket, Java 对象流
- 数据库：MySQL 8.x, JDBC
- 文档解析：PDFBox, Apache POI
- 测试：JUnit

主要依赖都写在 [pom.xml](pom.xml) 中。

## 代码结构

项目源码放在 `src/main/java/com/bdic` 下，按职责分成几个包：

```text
src/main/java/com/bdic
├── admin   客户端界面、服务端入口、业务编排
├── crypto  加密、密码哈希、客户端密钥管理
├── db      数据库连接、建表、Repository
├── model   网络消息、请求响应对象、文档模型
├── net     TLS Socket 创建和证书加载
└── text    文本抽取和关键词抽取
```

测试代码在：

```text
src/test/java/com/bdic
```

### `admin` 包：程序入口和界面逻辑

这个包是最适合新手从外往里看的地方，因为用户点击按钮后，大多数流程都会从这里开始。

- `AdminClientApp`
  - 客户端主窗口入口。
  - 负责连接服务端、显示登录/注册弹窗、创建三个 Tab。
  - 三个 Tab 分别是 `Upload`、`Search`、`Documents`。
  - 如果本地没有单独启动服务端，它会尝试自动启动嵌入式服务端。

- `Server`
  - 服务端入口。
  - 负责启动 TLS 监听、接收客户端连接、读取 `NetworkMessage`。
  - 根据消息类型分发到注册、登录、上传、搜索、下载、删除等逻辑。
  - 最后统一返回 `ServerResponse`。

- `DocumentServiceClient`
  - 客户端访问服务端的“小助手”。
  - UI 层不直接操作 socket，而是调用它的 `login`、`upload`、`search` 等方法。
  - 它负责把请求包装成 `NetworkMessage` 并读取响应。

- `UploadPanelController`
  - 上传页控制器。
  - 负责选择文件/文件夹、读取纯文本输入、启动后台上传任务。
  - 调用 `DocumentOperationService` 完成文档加密和关键词索引构建。

- `SearchPanelController`
  - 搜索页控制器。
  - 负责读取搜索框内容、生成搜索请求、渲染搜索结果。
  - 还负责文本预览、图片缩略图和结果区域的动态缩放。

- `DocumentsPanelController`
  - 文档管理页控制器。
  - 负责文档列表、下载、批量下载、删除、批量删除和重建索引。

- `DocumentOperationService`
  - 客户端文档业务核心。
  - 负责读取文件、判断文件类型、抽取正文、提取关键词、加密正文、生成 PEKS 索引。
  - 上传和重建索引都会用到它。

- `UiBusyStateManager`
  - 管理“任务正在运行”的状态。
  - 避免上传还没结束时又点搜索、下载等按钮。

- `UiScaleManager`
  - 根据窗口大小缩放字体、边距和布局间距。
  - 动态生成的搜索结果也会重新应用当前缩放比例。

- `UiComponentFactory`
  - 统一创建分组面板和按钮样式，减少 UI 代码重复。

- `NativeDialogHelper`
  - 封装系统文件/文件夹选择对话框。

### `crypto` 包：加密相关逻辑

- `ClientKeyManager`
  - 为每个用户在本地生成或加载密钥。
  - 密钥默认保存在 `client-keys/` 下，不应该提交到 Git。

- `DESUtil`
  - 对文档正文和关键词元数据做对称加密/解密。

- `PEKSUtil`
  - 将关键词转换成可搜索密文。
  - 将搜索词转换成 trapdoor。
  - 服务端用 trapdoor 和密文索引做匹配。

- `PasswordUtil`
  - 处理密码盐值和密码哈希。
  - 用于注册和登录校验。

### `db` 包：数据库访问

- `DatabaseManager`
  - 读取数据库配置。
  - 服务端启动时自动创建数据库和表。
  - 自动补齐旧版本缺失字段，并创建常用索引。

- `UserRepository`
  - 负责用户注册、查询用户密码哈希等操作。

- `EncryptedDataRepository`
  - 负责文档保存、搜索、列表、下载、删除。
  - 写入 `documents` 表和 `keyword_index` 表。

### `model` 包：客户端和服务端共同使用的数据对象

- `NetworkMessage`
  - 网络传输的统一消息对象。
  - 包含 `type` 和 `payload`。

- `ServerResponse`
  - 服务端统一响应对象。
  - 包含是否成功、提示消息和返回数据。

- `LoginRequest`
  - 登录/注册请求数据。

- `DocumentRequest`
  - 下载、删除等按文档 ID 操作时使用的请求数据。

- `EncryptedData`
  - 上传到服务端的加密文档实体。
  - 包含文档 ID、文件名、MIME 类型、加密正文、加密关键词元数据、PEKS 密文列表。

- `DocumentSummary`
  - 文档列表页使用的轻量摘要。
  - 不包含完整加密正文，适合列表展示。

- `SessionInfo`
  - 登录成功后返回的会话信息。

- `DocumentIdGenerator`
  - 生成展示用文档 ID。

### `net` 包：TLS 通信

- `SecureSocketProvider`
  - 统一创建服务端和客户端 TLS Socket。
  - 加载 `src/main/resources/tls/searchable-encryption-dev.p12` 中的开发证书。

### `text` 包：文本和关键词处理

- `DocumentTextExtractor`
  - 从文本、JSON、PDF、Word 文档中抽取正文。
  - 抽取失败时返回空字符串，不中断上传流程。

- `KeywordExtractor`
  - 从描述、文件名、正文中提取关键词。
  - 会转小写、去重、过滤太短的 token。
  - 对中文、日文、韩文等 CJK 文本会额外生成相邻双字片段，方便中文局部搜索。

## 功能流程详解

### 1. 注册和登录流程

```text
用户输入用户名/密码
        │
        ▼
AdminClientApp 显示认证弹窗
        │
        ▼
DocumentServiceClient 发送 REGISTER 或 LOGIN
        │
        ▼
Server 调用 UserRepository
        │
        ▼
PasswordUtil 校验密码哈希
        │
        ▼
Server 返回 ServerResponse 和 SessionInfo
```

登录成功后，客户端会加载或创建当前用户的本地密钥。后续上传、搜索、下载都依赖这组密钥。

### 2. 上传流程

```text
用户输入文本，或选择文件/文件夹
        │
        ▼
UploadPanelController 收集上传内容
        │
        ▼
DocumentOperationService 读取文件并提取关键词
        │
        ▼
DESUtil 加密正文
        │
        ▼
PEKSUtil 加密关键词并生成可搜索索引
        │
        ▼
DocumentServiceClient 发送 UPLOAD
        │
        ▼
Server 调用 EncryptedDataRepository 写入数据库
```

关键词来源包括：

- 用户填写的描述。
- 文件名。
- 可读取文件的正文内容。

为了支持前缀搜索，程序不仅保存完整关键词，还会生成从长度 2 开始的前缀 token。例如 `searchable` 会生成 `se`、`sea`、`sear` 等索引项。

### 3. 搜索流程

```text
用户输入搜索词
        │
        ▼
SearchPanelController 读取搜索框
        │
        ▼
PEKSUtil 生成 trapdoor
        │
        ▼
DocumentServiceClient 发送 SEARCH
        │
        ▼
Server 在 keyword_index 中匹配
        │
        ▼
客户端渲染命中文档和预览
```

搜索结果展示时：

- 文本文档会尝试解密并展示内容片段。
- 图片文档会展示缩略图。
- 二进制文件会提示需要下载后查看原文件。

### 4. 下载流程

```text
用户选择文档并点击 Download
        │
        ▼
DocumentsPanelController 发送 DOWNLOAD_DOCUMENT
        │
        ▼
Server 读取加密文档
        │
        ▼
客户端用 DESUtil 解密
        │
        ▼
保存到用户选择的路径
```

服务端不会解密文件，解密动作只在客户端发生。

### 5. 删除和重建索引

- 删除文档时，服务端删除 `documents` 中的文档记录，`keyword_index` 中的索引通过外键级联删除。
- 重建索引时，客户端下载完整文档对象，恢复或重新输入关键词，再生成新的 PEKS 索引并上传更新。

## 网络协议

客户端和服务端通过 `NetworkMessage` 传输对象。

`NetworkMessage` 主要有两个字段：

- `type`：这次请求要做什么。
- `payload`：这次请求携带的数据。

当前消息类型包括：

- `REGISTER`：注册。
- `LOGIN`：登录。
- `LOGOUT`：退出登录。
- `UPLOAD`：上传加密文档。
- `SEARCH`：搜索文档。
- `LIST_DOCUMENTS`：获取文档列表。
- `DOWNLOAD_DOCUMENT`：下载文档。
- `DELETE_DOCUMENT`：删除文档。
- `RESPONSE`：服务端统一响应。

服务端不管处理哪种请求，最终都会返回 `ServerResponse`，这样客户端处理成功、失败和错误消息会更统一。

## 数据库结构

服务端启动时，`DatabaseManager` 会自动创建业务数据库和三张核心表。

### `users`

保存用户登录信息：

- `username`：用户名，主键。
- `password_hash`：密码哈希。
- `password_salt`：密码盐。
- `created_at`：创建时间。

### `documents`

保存文档主体和元数据：

- `doc_id`：文档主键。
- `display_doc_id`：展示用文档 ID。
- `owner_username`：文档所属用户。
- `file_name`：原始文件名。
- `mime_type`：MIME 类型。
- `media_type`：简化分类，如 `text`、`document`、`image`、`binary`。
- `file_size`：原始文件大小。
- `encrypted_keyword_metadata`：加密后的关键词和描述元数据。
- `encrypted_content`：加密后的正文。
- `created_at`：上传时间。

### `keyword_index`

保存可搜索密文索引：

- `id`：自增主键。
- `doc_id`：关联文档。
- `peks_ciphertext`：关键词对应的可搜索密文。

## 运行方式

### 环境要求

- JDK 17
- Maven 3.8+
- MySQL 8.x

### 1. 克隆项目并编译

```bash
git clone https://github.com/daaaaaataaaaaa/searchable-encryption.git
cd searchable-encryption
mvn clean compile
```

### 2. 配置数据库

`DatabaseManager` 的配置读取优先级是：

```text
JVM 系统属性 > 环境变量 > 默认值
```

推荐使用环境变量配置数据库连接，尤其是密码。

PowerShell 示例：

```powershell
$env:SE_DB_HOST="127.0.0.1"
$env:SE_DB_PORT="3306"
$env:SE_DB_NAME="searchable_encryption"
$env:SE_DB_USER="root"
$env:SE_DB_PASSWORD="your_password"
```

可配置项如下：

- `se.db.host` / `SE_DB_HOST`
- `se.db.port` / `SE_DB_PORT`
- `se.db.name` / `SE_DB_NAME`
- `se.db.user` / `SE_DB_USER`
- `se.db.password` / `SE_DB_PASSWORD`

如果你使用 IDE，也可以把这些环境变量配置到运行配置里。

### 3. 运行客户端

最简单的方式是直接运行客户端：

```bash
mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.bdic.admin.AdminClientApp
```

客户端默认连接 `127.0.0.1:12345`。如果没有检测到独立服务端，它会尝试在同一个 JVM 中启动嵌入式服务端。

也可以在 IDE 中直接运行：

```text
com.bdic.admin.AdminClientApp.main()
```

### 4. 单独运行服务端，可选

如果你想观察客户端和服务端两个进程之间的通信，可以先单独启动服务端：

```bash
mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.bdic.admin.Server
```

然后再启动客户端。

## 使用步骤

### 第一次使用

1. 确认 MySQL 已启动。
2. 配置数据库环境变量。
3. 启动客户端。
4. 在弹窗中选择 `Register` 注册用户。
5. 注册成功后登录进入主界面。

### 上传一个纯文本

1. 进入 `Upload` 页。
2. 在 `Description` 中填写描述或关键词。
3. 在文本框中输入正文。
4. 点击 `Upload Document`。
5. 成功后可以到 `Documents` 页刷新列表查看。

### 上传文件或文件夹

1. 进入 `Upload` 页。
2. 点击 `Import`。
3. 选择 `Import Files` 或 `Import Folder`。
4. 填写描述，可选。
5. 点击 `Upload Document`。

### 搜索文档

1. 进入 `Search` 页。
2. 输入关键词、文件名前缀或文档 ID 片段。
3. 点击 `Search` 或按回车。
4. 在结果列表中查看命中文档。

### 下载或删除文档

1. 进入 `Documents` 页。
2. 点击 `Refresh List`。
3. 选择一个或多个文档。
4. 点击 `Download`、`Delete` 或 `Rebuild Index`。

## 开发命令

```bash
mvn compile
mvn test
mvn package
```

常用说明：

- `mvn compile`：只编译主代码。
- `mvn test`：编译并运行测试。
- `mvn package`：编译、测试并打包。

## 测试覆盖

当前测试位于 `src/test/java/com/bdic`：

- `CryptoTest`：加密工具相关测试。
- `DatabaseRepositoryTest`：数据库仓储相关测试。
- `DocumentIdGeneratorTest`：文档 ID 生成测试。
- `KeywordExtractorTest`：关键词抽取测试。
- `SecureSocketProviderTest`：TLS Socket 配置测试。

## 常见问题

### 1. 启动时报数据库连接失败

先检查：

- MySQL 是否已经启动。
- `SE_DB_HOST`、`SE_DB_PORT`、`SE_DB_USER`、`SE_DB_PASSWORD` 是否正确。
- 当前数据库用户是否有创建数据库和建表权限。

### 2. 上传大文件失败

大文件会受到 MySQL `max_allowed_packet` 等配置影响。可以调大 MySQL 相关限制，或先用较小文件测试。

### 3. 为什么服务端不能直接看到文件内容

文件正文在客户端用 `DESUtil` 加密后才上传。服务端保存的是 `encrypted_content`，没有客户端本地密钥就不能还原明文。

### 4. 为什么搜索还能命中

上传时客户端会把关键词转换为 PEKS 密文索引。搜索时客户端把搜索词转换为 trapdoor。服务端用 trapdoor 和索引做匹配，因此可以判断是否命中。

### 5. `client-keys/` 目录是什么

这是客户端为用户保存本地密钥的目录。它决定了你能否解密自己上传的文档，不要提交到 Git，也不要随意删除。

## 已知边界

- Java 对象流适合教学和内网实验，开放网络环境建议换成更标准的协议和序列化格式。
- 当前证书是开发用途，生产环境需要替换证书和密钥管理方案。
- 本项目强调链路完整性和可理解性，不等同于经过安全审计的生产级加密系统。
- 大文件上传能力受数据库、内存和网络配置共同影响。

## 安全与提交建议

- 不要提交 `client-keys/`、`target/`、本地证书、数据库密码和其他密钥文件。
- 数据库密码建议通过环境变量或 JVM 参数传入，不要写死到公开仓库。
- 如果历史提交中出现过密钥或证书，建议清理 Git 历史并轮换密钥。

## 推荐阅读顺序

如果你是第一次看这个项目，可以按这个顺序读代码：

1. `AdminClientApp`：先理解程序如何启动，以及三个页面怎么创建。
2. `UploadPanelController`：看一次上传从按钮点击到后台任务的过程。
3. `DocumentOperationService`：理解文件如何变成加密文档。
4. `DocumentServiceClient` 和 `NetworkMessage`：理解客户端如何发请求。
5. `Server`：理解服务端如何接收请求并分发。
6. `EncryptedDataRepository`：理解数据最终如何落库。
7. `SearchPanelController`：理解搜索结果如何回来并展示。

这样读会比一开始就钻进加密算法更容易建立整体感觉。
