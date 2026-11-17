# 发票 OCR 识别系统

基于 **Java 17 + Spring Boot 3.3 + MySQL 8 + Flyway** 的发票识别与复核系统。上传发票图片 / PDF / OFD，自动提取字段，并提供查重、验真、人工复核与导出。

图片 OCR 默认调用 **PaddleOCR-VL** 在线 API，也支持百度 OCR、Tesseract 和 mock。数字版 PDF / OFD 直接读取文本层，不依赖外部 OCR。

## 功能

- 上传 JPG/PNG/BMP/PDF/OFD，文件头校验、SHA-256 去重
- OCR 引擎可切换：`paddle`（默认）/ `baidu` / `tesseract` / `mock`，失败可配置降级
- 规则解析发票代码、号码、日期、购销方、税号、金额、税率、商品明细，字段级置信度
- 发票查重（精确 + 指纹 + 字段哈希）
- 验真渠道：`mock` / 通用 HTTP
- 人工复核留痕、CSV/XLSX 导出、审计日志、JWT 权限控制
- 内置 Web 控制台与 Swagger 文档

## 快速开始

环境：JDK 17、Maven 3.9+、MySQL 8.0+。

```bash
# 1. 建库
mysql -uroot -p -e "CREATE DATABASE invoice_ocr CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"

# 2. 构建运行
mvn -DskipTests package
java -jar target/invoice-ocr-0.1.0.jar
```

打开 <http://localhost:8080/>，默认账号：

| 账号 | 密码 | 角色 |
|---|---|---|
| `admin` | `Admin@123` | 管理员 |
| `reviewer` | `Review@123` | 复核员 |
| `submitter` | `Submit@123` | 提交人 |
| `auditor` | `Audit@123` | 审计查看 |

Swagger：<http://localhost:8080/swagger-ui.html>

## PaddleOCR Token

不要把 Token 写进 `application.yml` 或提交到 Git。按以下任一方式配置：

1. **推荐：本地文件。** 在项目根目录创建 `paddleocr-token.txt`，内容为 Token。该文件已在 `.gitignore` 中，存在时优先使用。
2. **环境变量：** `PADDLEOCR_TOKEN=...`
3. **管理后台：** 系统配置中的 `ocr.paddle.token`（AES-GCM 加密存库）

Token 读取优先级：本地文件 > 管理后台配置 > 环境变量。

## 配置

常用环境变量：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/invoice_ocr...` | 数据库地址 |
| `DB_USERNAME` / `DB_PASSWORD` | `root` / `123456` | 数据库账号 |
| `JWT_SECRET` | 内置开发密钥 | 生产必须替换 |
| `APP_ENCRYPTION_KEY` | 内置开发密钥 | 敏感配置加密，生产必须设置 |
| `APP_STORAGE_ROOT` | `./data/storage` | 原件与 OCR 结果目录 |
| `APP_MAX_FILE_SIZE` | `10485760` | 单文件大小限制（字节） |
| `PADDLEOCR_TOKEN_FILE` | `./paddleocr-token.txt` | 本地 Token 文件 |
| `PADDLEOCR_TOKEN` | 空 | Token 环境变量回退 |
| `PADDLEOCR_MODEL` | `PaddleOCR-VL-1.6` | PaddleOCR 模型 |
| `PADDLEOCR_BASE_URL` | `https://paddleocr.aistudio-app.com/api/v2/ocr/jobs` | 任务接口 |
| `PADDLEOCR_POLL_INTERVAL_SECONDS` | `3` | 轮询间隔 |
| `PADDLEOCR_TIMEOUT_SECONDS` | `180` | 单任务超时 |
| `TESSERACT_DATAPATH` | 空 | Tesseract `tessdata` 目录 |
| `TESSERACT_LANGUAGE` | `chi_sim+eng` | Tesseract 语言包 |
| `APP_SEED_ENABLED` | `true` | 是否初始化演示数据 |

管理后台可修改的运行时配置（立即对新任务生效）：

- `ocr.engine` / `ocr.fallback-engine`
- `ocr.confidence.threshold`
- `ocr.pdf.min-text-chars` / `ocr.pdf.max-pages` / `ocr.ofd.max-images`
- `ocr.paddle.token-file` / `ocr.paddle.token` / `ocr.paddle.model` / `ocr.paddle.base-url`
- `ocr.paddle.poll-interval-seconds` / `ocr.paddle.timeout-seconds`
- `ocr.paddle.use-doc-orientation-classify` / `use-doc-unwarping` / `use-chart-recognition`
- `ocr.tesseract.datapath` / `ocr.tesseract.language`
- `ocr.baidu.api-key` / `ocr.baidu.secret-key`
- `preprocess.enabled` / `preprocess.deskew` / `preprocess.stamp-suppression`
- `dedup.fallback.enabled` / `dedup.field-hash.enabled`
- `verify.trigger` / `verify.channel` / `verify.http.*`
- `storage.retention-years` / `export.max-rows`

密钥类配置在接口中只返回 `******`。

## OCR 引擎

- **`paddle`（默认）**：提交异步任务 → 轮询 → 下载 JSONL。Markdown / HTML 表格会先展平为普通文本行再解析。Token 配置见上文。
- **`baidu`**：百度增值税发票识别 `vat_invoice`，需要 API Key / Secret Key。
- **`tesseract`**：本地离线引擎，需安装 Tesseract 5.x 与 `chi_sim` 语言包。
- **`mock`**：联调用，图片返回空文本，不伪造字段。

主引擎异常时可自动降级到 `ocr.fallback-engine`。任务最终失败会保留原文件与错误信息，可在详情页重新识别。

## 目录结构

```
src/main/java/com/invoiceocr/
├── config/      # 配置、安全、线程池、初始化
├── domain/      # JPA 实体与枚举
├── dto/         # API 数据传输对象
├── ocr/         # OCR 适配层、PDF/OFD 抽取、编排
├── parser/      # 字段规则解析
├── preprocess/  # 图像预处理
├── repo/        # 数据访问
├── security/    # JWT 与当前用户
├── service/     # 上传、识别、查重、复核、导出、验真、审计
├── verify/      # 验真适配层
└── web/         # REST 控制器
src/main/resources/
├── application.yml
├── db/migration/
└── static/      # 内置 Web 控制台
```

## 已知限制

- 图片 OCR 依赖公网与有效 PaddleOCR Token，识别耗时受平台排队影响
- 商品明细为启发式解析；无表格线的票面可能拆分不准
- 扫描版 OFD 仅提取内嵌图片，未做完整渲染
- 验真 `mock` 渠道不连接税局，生产需配置真实 HTTP 渠道
- 上传异步返回 202，客户端需轮询结果
- 保留期清理、JWT 吊销、Redis 缓存等尚未实现
