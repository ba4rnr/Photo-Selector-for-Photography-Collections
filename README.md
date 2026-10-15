# 摄影集照片筛选器

Photo Selector —— 一个用来从摄影集里挑照片的筛选器：浏览成组照片、横向比较、快速标记留用或淘汰，最后导出筛选结果。

项目目前处于起步阶段，仓库里只有标准的工程骨架。

## 环境要求

| 项目 | 版本 |
| --- | --- |
| JDK | 25（`maven.compiler.release=25`） |
| 构建工具 | Apache Maven 3.9+ |
| 版本管理 | Git |

IntelliJ IDEA 自带的 Maven 可以直接使用，无需单独安装（本机路径示例：
`D:\Applications\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3`）。

## 目录结构

```
Photo Selector/
├── pom.xml                   Maven 构建配置
├── README.md
├── .gitignore
└── src
    ├── main
    │   ├── java               主代码（com.photoselector 包）
    │   └── resources          运行时资源（图标、样式表等）
    └── test
        ├── java               测试代码
        └── resources          测试资源
```

## 常用命令

```bash
mvn clean package     # 编译、跑测试并打包
mvn test              # 只跑测试
mvn clean compile     # 只编译
java -jar target/photo-selector.jar   # 运行
```

## 开发约定

- 源码统一使用 UTF-8 编码。
- 主代码放在 `src/main/java`，测试放在 `src/test/java`，两者包结构保持一致。
- 本地的照片素材放在 `photos/`、`samples/` 下，已在 `.gitignore` 中排除，不会误传进仓库。
