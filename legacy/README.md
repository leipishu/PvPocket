# legacy/ —— 需要另一套 Gradle 的版本

这里放**不能和现代 cell 共用一个 Gradle 进程**的版本。目前只有 1.20.1。

## 为什么分开

1.20.1 只能用 Forge（NeoForge 从 1.20.2 才有），而 ForgeGradle 6 绑 Gradle 8.x；现代 Loom 与
NeoGradle 要 Gradle 9。一个 Gradle 进程只能跑一个版本，所以它需要自己的 `gradlew`。

## 目录

```
legacy/
├── 1.20.1/
│   ├── settings.gradle        # 自己的根
│   ├── gradle/wrapper/        # Gradle 8.x
│   └── versions/1.20.1-forge/build.gradle
└── README.md
```

共享源码通过目录而不是项目依赖引用（`sourceSets.main.java.srcDirs += "../../../src/main/java"`），
这样 `src/` 只有一份。它自己的 Stonecutter 会让共享源码里的 `//?` 条件在这个根里同样生效。

## 还没建

建之前先确认两件事：

- **FG6 与 Stonecutter 能不能共存**。不能的话退路是 1.20.1 不共享源码、从 1.21.1 复制一份冻结。
- **语言级别锁在 17**：`common` 的语言级别是 17，1.20.1 运行时也是 17。现代 cell 是 21，
  所以共享源码里不能出现 21 才有的语法（record pattern、`switch` 的模式匹配等）。
