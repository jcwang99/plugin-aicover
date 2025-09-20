# aicover

aicover - Halo 插件

## 简介

这是一个基于 Halo 的插件项目。

## 开发环境

- Java 21+
- Node.js 18+
- pnpm

## 开发

```bash
# 构建插件
./gradlew build

# 开发前端
cd ui
pnpm install
pnpm dev
```

## 构建

```bash
./gradlew build
```

构建完成后，可以在 `build/libs` 目录找到插件 jar 文件。

## 许可证

[GPL-3.0](./LICENSE) © jacylunatic 


# 插件扩展手册：如何添加新的 AI 绘图平台

本手册将指导您如何为 AI Cover 插件添加一个新的图片生成平台（例如 DALL-E, Midjourney API 等）。得益于插件现有的模块化设计，整个过程非常简单，主要分为三个步骤。

## 步骤一：后端 - 创建新的“驱动程序”

首先，我们需要为新的 AI 平台创建一个专属的“驱动程序”，它将负责处理所有与该平台相关的 API 调用。

### 1. 创建平台专属的设置模型

为新平台创建一个 Java 类，用于承载其在 Halo 后台的设置（例如 API Key）。

-   **操作**: 在 `src/main/java/com/jacylunatic/aicover/model/` 目录下，创建一个新的 Java 文件。
    
-   **示例** (`NewPlatformSetting.java`):
    
    ```
    package com.jacylunatic.aicover.model;
    
    import lombok.Data;
    
    @Data
    public class NewPlatformSetting {
        // 这个 GROUP 常量必须与您稍后在 setting.yaml 中定义的组名完全匹配
        public static final String GROUP = "newplatform-settings";
    
        private String apiKey;
        // 如果需要，还可以添加其他配置，如 apiSecret, endpointUrl 等
    }
    
    ```
    

### 2. 创建平台专属的服务实现

这是最核心的一步。为新平台创建一个服务类，它将实现我们定义好的 `ImageGenerator` 接口。

-   **操作**: 在 `src/main/java/com/jacylunatic/aicover/service/generators/` 目录下，创建一个新的 Java 文件。
    
-   **示例** (`NewPlatformImageGenerator.java`):
    
    ```
    package com.jacylunatic.aicover.service.generators;
    
    import com.jacylunatic.aicover.model.NewPlatformSetting;
    import com.jacylunatic.aicover.model.ProgressUpdate;
    import com.jacylunatic.aicover.service.ImageGenerator;
    import lombok.RequiredArgsConstructor;
    import org.springframework.web.reactive.function.client.WebClient;
    import reactor.core.publisher.Flux;
    import run.halo.app.plugin.ReactiveSettingFetcher;
    // ... 其他必要的 import
    
    @RequiredArgsConstructor // 确保构造函数被 Lombok 生成
    public class NewPlatformImageGenerator implements ImageGenerator {
    
        private final ReactiveSettingFetcher settingFetcher;
        // ... 其他可能需要的依赖，如 WebClient, ObjectMapper
    
        @Override
        public String getPlatformIdentifier() {
            // 返回一个全小写的、唯一的平台标识符
            return "newplatform";
        }
    
        @Override
        public Flux<ProgressUpdate> generateImage(String prompt, String model, String size) {
            // 1. 使用 settingFetcher 获取您刚刚创建的 NewPlatformSetting
            // 2. 编写调用新平台 API 的完整逻辑（请求、错误处理、返回 ProgressUpdate 流）
            //    您可以完全参考 TongyiImageGenerator.java 或 SiliconFlowImageGenerator.java 的实现方式。
            // 3. 成功时，返回 ProgressUpdate.intermediateSuccess(...) 或 ProgressUpdate.finalSuccess(...)
            // 4. 失败时，返回 ProgressUpdate.error(...)
        }
    }
    
    ```
    

### 3. 向“管理器”注册新的服务

最后，我们需要告诉我们的“总指挥”(`AiImageService`)，现在有了一位新员工。

-   **操作**: 打开 `src/main/java/com/jacylunatic/aicover/service/AiImageService.java` 文件。
    
-   **修改内容**: 找到 `init()` 方法，在 `List.of(...)` 中，添加一个您新创建的服务实例。
    
    ```
    // AiImageService.java
    @PostConstruct
    public void init() {
        this.imageGenerators = List.of(
            new TongyiImageGenerator(settingFetcher),
            new SiliconFlowImageGenerator(settingFetcher),
            new NewPlatformImageGenerator(settingFetcher) // <-- 在这里添加新行
        );
        // ... (下面的日志代码会自动更新)
    }
    
    ```
    

## 步骤二：后台 - 配置插件

现在后端代码已经准备就绪，我们需要在 Halo 的后台为新平台添加入口。

### 1. 添加设置界面

-   **操作**: 打开 `src/main/resources/config/setting.yaml` 文件。
    
-   **修改内容**: 仿照 `tongyi-settings` 或 `siliconflow-settings`，为您的新平台添加一个新的设置组。
    
    ```
    # setting.yaml
    
    # ... (其他设置)
    
    - group: newplatform-settings # 必须与 NewPlatformSetting.java 中的 GROUP 常量一致
      label: 新平台 (NewPlatform) 设置
      formSchema:
        - $formkit: text
          name: apiKey
          label: API Key
          placeholder: "请输入您的新平台 API Key"
    
    ```
    

### 2. 添加可用模型

-   **操作**: 同样在 `src/main/resources/config/setting.yaml` 文件中。
    
-   **修改内容**: 找到 `master-settings` 组下的 `models` 文本域，在其中添加您新平台的模型。
    
    ```
    # setting.yaml
    
          # ...
          label: 可用模型列表
          value: |
            tongyi,通义万相 V1,wanx-v1
            siliconflow,Stable Diffusion XL,stable-diffusion-xl
            newplatform,新平台的模型,new-model-id  # <-- 在这里添加新行
    
    ```
    

## 步骤三：前端 - （几乎无需改动）

得益于我们之前的设计，前端通常**不需要任何代码改动**。

-   **自动适配**: 当您完成后台的修改并重启 Halo 后，前端的弹窗会自动请求 `/api/plugins/aicover/models` 接口，获取到您新添加的模型列表。
    
-   **自动分组**: `<optgroup>` 会自动根据新的平台标识符（`newplatform`）创建一个新的模型分组。
    
-   **自动发送**: 当您选中新平台的模型时，前端会自动将 `newplatform:new-model-id` 这样的组合 ID 发送给后端，我们的“管理器”(`AiImageService`) 也能正确地解析并分发任务。
    

完成以上所有步骤后，您只需重新编译并安装插件，即可享受接入新平台的成果！