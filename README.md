# 摄影集照片筛选器

Photo Selector —— 一个本地运行的桌面工具，服务于「拍摄 → 挑片 → 归档成片」里的挑片环节：
打开一个拍摄文件夹，快速过片，把要留的挑进目标文件夹，并自动重新编号。

## 简介

- 素材目录树支持双击浏览每个子文件夹；底部胶片条像 Lightroom 一样横向滚动、按子文件夹分组、可收起
- 挑中的照片进入目标文件夹的虚拟工作区（"中间商"），排序、编号、撤销都在内存里即时完成，不碰磁盘
- 按「保存」或关闭程序时改动才落到磁盘：复制新增照片、按新顺序重命名、永久删除被拖进垃圾桶的照片
- 运行期间持续监听目标文件夹，在外部手动增删改名，界面会立即跟上
- 界面配色取自 Capture One 的暗色主题；首版支持 JPG，DNG（读取内嵌全尺寸预览）随后接入

项目处于起步阶段，核心域模型先行，界面与文件操作随后逐块补齐。

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
