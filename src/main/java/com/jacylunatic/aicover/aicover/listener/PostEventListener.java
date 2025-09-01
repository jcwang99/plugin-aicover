package com.jacylunatic.aicover.aicover.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.event.post.PostPublishedEvent;
import run.halo.app.extension.ExtensionClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostEventListener {

    private final ExtensionClient client;

    /**
     * 监听文章发布事件。
     * 这是 Halo 2.21+ 中处理文章创建和更新的正确方式。
     *
     * @param event 文章发布事件对象
     */
    @EventListener(PostPublishedEvent.class)
    public void onPostPublished(PostPublishedEvent event) {
        // 使用 ExtensionClient 和事件中的文章名称来获取完整的文章对象
        // 正确的方法是 event.getName()
        Mono.justOrEmpty(client.fetch(Post.class, event.getName()))
            .flatMap(post -> {
                // 检查封面是否存在
                if (!StringUtils.hasText(post.getSpec().getCover())) {
                    log.info("检测到文章 [{}] 已发布但未设置封面。", post.getMetadata().getName());
                    // 注意：后端事件监听器是异步的，不能直接触发前端 UI。
                    // 真正的 UI 触发应该由前端在点击“发布”按钮时进行检查。
                    // 这里可以作为服务器端的检查点，用于记录日志等。
                }
                return Mono.empty();
            })
            .doOnError(e -> log.error("处理文章发布事件时出错: {}", e.getMessage()))
            .subscribe();
    }
}

