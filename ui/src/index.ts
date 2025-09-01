import { createApp, h, ref } from 'vue'
import AiCoverModal from './views/components/AiCoverModal.vue'

// 标记，用于防止在我们用代码自动点击按钮时，陷入无限循环
let isProceedingWithCover = false;

/**
 * --- 新增功能 ---
 * 注入 CSS 修复，强制使弹窗可见。
 * 这可以解决因组件内部样式（如 opacity: 0）导致弹窗不可见的问题。
 */
function injectStyleFix() {
    const styleId = 'ai-cover-style-fix';
    if (document.getElementById(styleId)) return;
    const style = document.createElement('style');
    style.id = styleId;
    style.innerHTML = `
        .ai-cover-modal-overlay {
            opacity: 1 !important;
            transition: opacity 0.3s ease !important;
            background-color: rgba(0, 0, 0, 0.6) !important;
        }
    `;
    document.head.appendChild(style);
    console.log("[Debug] 已注入弹窗可见性修复样式。");
}


/**
 * 寻找文章设置中的“封面”输入框 (Debug Mode)
 */
function findCoverInput(): HTMLInputElement | null {
  console.groupCollapsed('[Debug] 正在查找 "封面" 输入框...');

  // 根据您的提示，我们将搜索范围限定在设置弹窗/面板内。
  const settingsPanel = document.querySelector('.modal-content, .post-settings-panel');
  const searchContext = settingsPanel || document;

  if (settingsPanel) {
      console.log("已将搜索范围限定在设置面板内:", settingsPanel);
  } else {
      console.log("未找到特定设置面板，将在整个文档范围内搜索。");
  }

  // 策略 0: 直接通过 name="cover" 查找，这通常最可靠
  const directInput = searchContext.querySelector('input[name="cover"]') as HTMLInputElement | null;
  if (directInput) {
    console.log(`%c🎉 成功: 已通过 'name="cover"' 属性直接找到输入框!`, 'color: green; font-weight: bold;', directInput);
    console.groupEnd();
    return directInput;
  }
  console.log("...通过 name='cover' 直接查找失败，继续使用 label 策略。");


  const labels = Array.from(searchContext.querySelectorAll("label, .formkit-label"));
  console.log(`在当前范围内共找到 ${labels.length} 个潜在的 label 元素。`);
  
  // 策略 1: 查找文本完全匹配 "封面图" 的 label
  const coverLabel = labels.find(label => {
    const text = label.textContent?.trim();
    if (text === "封面图") {
      console.log(`%c🎉 找到一个文本为 "封面图" 的 label:`, 'color: green;', label);
      return true;
    }
    return false;
  });
  
  if (coverLabel) {
    // 策略 1a: 通过 "for" 属性查找
    const inputId = coverLabel.getAttribute("for");
    console.log(`Label 的 "for" 属性是: ${inputId}`);
    if (inputId) {
      const inputElement = document.getElementById(inputId);
      console.log(`通过 ID "${inputId}" 找到的元素是:`, inputElement);
      if (inputElement instanceof HTMLInputElement) {
        console.log("%c成功: 已通过 'for' 属性策略找到输入框!", "color: green; font-weight: bold;");
        console.groupEnd();
        return inputElement;
      }
    }

    // 策略 1b: 通过父级元素遍历查找
    console.log("正在尝试父级元素遍历策略...");
    const parent = coverLabel.closest('.formkit-wrapper, div.form-item, div.form-group, .formkit-outer');
    console.log("找到的最接近的父容器是:", parent);
    if (parent) {
      const input = parent.querySelector('input[type="text"], input[type="url"]') as HTMLInputElement | null;
      console.log("在父容器内找到的 input 是:", input);
      if (input) {
          console.log("%c成功: 已通过父级元素遍历策略找到输入框!", "color: green; font-weight: bold;");
          console.groupEnd();
          return input;
      }
    }
  } else {
      console.log('%c...在当前范围内未找到任何包含 "封面图" 的 label。', 'color: orange;');
  }

  console.warn("AI Cover 插件：未能定位到封面输入框。");
  console.groupEnd();
  return null;
}

/**
 * 寻找“发布”或“更新”按钮
 */
function findPublishButton(): HTMLButtonElement | null {
    const buttons = document.querySelectorAll<HTMLButtonElement>('button');
    for (const button of buttons) {
        const text = button.textContent?.trim();
        if ((text === '发布' || text === '更新') && !button.disabled) {
             return button;
        }
    }
    return null;
}

/**
 * 创建并挂载 Vue 应用，该应用负责管理弹窗
 */
function setupModal(publishButton: HTMLButtonElement) {
  let container = document.getElementById("ai-cover-modal-container");
  if (container) {
      document.body.removeChild(container);
  }

  container = document.createElement("div");
  container.id = "ai-cover-modal-container";
  document.body.appendChild(container);

  const isModalVisible = ref(false);

  const App = {
    setup() {
      const handleUseImage = (imageUrl: string) => {
        const coverInput = findCoverInput();
        if (coverInput) {
          coverInput.value = imageUrl;
          coverInput.dispatchEvent(new Event('input', { bubbles: true }));
          coverInput.dispatchEvent(new Event('change', { bubbles: true }));
          isModalVisible.value = false;

          setTimeout(() => {
            isProceedingWithCover = true;
            publishButton.click();
          }, 100);
        } else {
          alert("未能找到封面输入框，请手动复制图片链接。");
        }
      };

      const handleClose = () => {
        isModalVisible.value = false;
      };

      return () =>
        h(AiCoverModal, {
          visible: isModalVisible.value,
          onClose: handleClose,
          "onUse-image": handleUseImage,
        });
    },
  };

  const app = createApp(App);
  app.mount(container);

  return (visible: boolean) => {
    isModalVisible.value = visible;
  };
}

/**
 * 插件初始化函数
 */
function initializePlugin() {
    console.log("✅ AI Cover Plugin (Vite): 脚本已加载，正在初始化 (Debug Mode)...");

    injectStyleFix(); // 在初始化时注入样式修复

    const observer = new MutationObserver(() => {
        const publishButton = findPublishButton();

        if (publishButton) {
            console.log("%c🎉 成功定位到发布/更新按钮!", "color: green; font-weight: bold;", publishButton);
            
            observer.disconnect();
            console.log("...观察者已停止。");

            const showModal = setupModal(publishButton);

            publishButton.addEventListener("click", (event) => {
                if (isProceedingWithCover) {
                    isProceedingWithCover = false;
                    return;
                }
                
                // --- 核心修正 ---
                // 我们不再需要 setTimeout，因为 preventDefault 必须同步调用。
                // 我们现在假设点击“发布”按钮后，设置面板的内容已经或即将
                // 在同一个事件循环内出现在 DOM 中，使得同步查找成为可能。

                const coverInput = findCoverInput();

                if (coverInput && !coverInput.value) {
                    console.log("AI Cover 插件：检测到封面为空，已拦截发布操作。");
                    event.preventDefault();
                    event.stopPropagation();
                    showModal(true);
                } else if (!coverInput) {
                    // 如果同步找不到，我们做一个延迟尝试作为备用方案
                     setTimeout(() => {
                        const coverInputRetry = findCoverInput();
                        if (coverInputRetry && !coverInputRetry.value) {
                           showModal(true);
                        }
                     }, 100);
                } else {
                    console.log("[Debug] 封面输入框有值，不进行拦截。值为:", coverInput.value);
                }

            }, true);
        }
    });

    observer.observe(document.body, {
        childList: true,
        subtree: true,
    });
}

// 直接执行初始化函数。
initializePlugin();

