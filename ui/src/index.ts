import { createApp, h, ref } from 'vue'
import AiCoverModal from './views/components/AiCoverModal.vue'

/**
 * --- 全新设计 ---
 * 这个脚本不再拦截“发布”按钮。
 * 它的新任务是：
 * 1. 持续观察页面，等待“封面图”输入框出现。
 * 2. 在输入框旁边注入一个“AI 生成”按钮。
 * 3. 点击这个新按钮时，弹出 AI 封面生成窗口。
 * 4. 自动处理 SPA 导航，当用户离开再回来时，能重新注入按钮。
 */

// 全局变量，用于存储 Vue 弹窗的 show/hide 控制函数
let showModal: ((visible: boolean) => void) | null = null;

/**
 * 寻找“封面图”输入框所在的容器。
 * 我们需要找到 '.formkit-inner' 以便在其中注入按钮。
 * @returns {HTMLElement | null}
 */
function findCoverInputContainer(): HTMLElement | null {
  const coverInput = document.querySelector('input[name="cover"]') as HTMLInputElement | null;
  if (coverInput) {
    // 根据用户提供的 HTML 结构，按钮应该被注入到这个 div 中
    return coverInput.closest('.formkit-inner');
  }
  return null;
}

/**
 * 注入按钮所需的 CSS 动画和样式。
 * 这个函数只会执行一次。
 */
function injectAiButtonStyles() {
  const styleId = 'ai-cover-button-styles';
  if (document.getElementById(styleId)) {
    return;
  }
  const style = document.createElement('style');
  style.id = styleId;
  style.innerHTML = `
    @keyframes gradient-animation {
      0% { background-position: 0% 50%; }
      50% { background-position: 100% 50%; }
      100% { background-position: 0% 50%; }
    }

    #ai-cover-trigger-btn {
      height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 0 16px;
      border: none;
      border-left: 1px solid #e5e7eb;
      background-color: transparent;
      font-weight: 600;
      font-size: 1rem;
      cursor: pointer;
      transition: transform 0.2s ease;
      
      /* Gradient Text Styling */
      background-image: linear-gradient(-45deg, #a881ff, #66a6ff, #89f7fe, #e178ff);
      background-size: 200% auto;
      -webkit-background-clip: text;
      background-clip: text;
      color: transparent;
      animation: gradient-animation 5s ease infinite;
    }

    #ai-cover-trigger-btn:hover {
      transform: scale(1.05);
    }
  `;
  document.head.appendChild(style);
}


/**
 * 创建并注入“AI 生成”按钮
 * @param {HTMLElement} container - 封面图输入框的容器
 */
function injectAiButton(container: HTMLElement) {
  // 如果按钮已经存在，则不重复注入
  if (document.getElementById('ai-cover-trigger-btn')) {
    return;
  }

  console.log("AI Cover 插件：找到封面图容器，正在注入'AI'按钮...");

  const button = document.createElement('button');
  button.id = 'ai-cover-trigger-btn';
  button.type = 'button'; // 防止触发表单提交
  button.innerHTML = 'AI'; // 移除了图标
  button.title = 'AI 生成封面图';

  // 为按钮添加点击事件，用于弹出窗口
  button.addEventListener('click', () => {
    if (showModal) {
      console.log("AI Cover 插件：'AI'按钮被点击，正在打开弹窗...");
      showModal(true);
    } else {
      console.error("AI Cover 插件：弹窗尚未初始化。");
    }
  });

  // 将按钮注入到容器中
  container.appendChild(button);
}

/**
 * 创建并挂载 Vue 弹窗应用。
 * 这个函数只应被调用一次。
 */
function setupModal() {
  if (document.getElementById("ai-cover-modal-container")) {
    return;
  }

  const container = document.createElement("div");
  container.id = "ai-cover-modal-container";
  document.body.appendChild(container);

  const isModalVisible = ref(false);

  const App = {
    setup() {
      const handleUseImage = (imageUrl: string) => {
        const coverInput = document.querySelector('input[name="cover"]') as HTMLInputElement | null;
        if (coverInput) {
          coverInput.value = imageUrl;
          coverInput.dispatchEvent(new Event('input', { bubbles: true }));
          coverInput.dispatchEvent(new Event('change', { bubbles: true }));
          console.log("AI Cover: 封面已自动回填。");
          isModalVisible.value = false;
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

  createApp(App).mount(container);

  // 将控制函数暴露到全局
  showModal = (visible: boolean) => {
    isModalVisible.value = visible;
  };
}

/**
 * 插件初始化函数
 */
function initializePlugin() {
    console.log("✅ AI Cover Plugin (Vite): 脚本已加载，开始持续监控页面...");

    // 首次加载时，先挂载 Vue 弹窗实例和按钮样式
    setupModal();
    injectAiButtonStyles();

    // 使用 MutationObserver 持续监控 DOM 变化
    const observer = new MutationObserver(() => {
        // 每次 DOM 变化时，都尝试寻找容器并注入按钮
        const coverInputContainer = findCoverInputContainer();
        if (coverInputContainer) {
            injectAiButton(coverInputContainer);
        }
    });

    observer.observe(document.body, {
        childList: true,
        subtree: true,
    });
}

// 直接执行初始化函数。
initializePlugin();

