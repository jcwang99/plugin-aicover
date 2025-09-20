<template>
  <Transition name="modal-fade">
    <div v-if="visible" class="ai-cover-modal-overlay" @click.self="closeModal">
      <div class="ai-cover-modal-content">
        <h2 class="ai-cover-modal-title">AI 生成封面</h2>
        
        <div class="ai-cover-form-group">
          <label for="ai-cover-prompt">提示词 (Prompt)</label>
          <input 
            type="text" 
            id="ai-cover-prompt" 
            class="ai-cover-input" 
            v-model="prompt"
            placeholder="例如：一只可爱的猫，赛博朋克风格"
            :disabled="isLoading"
          />
        </div>

        <div class="ai-cover-form-group-inline">
          <div class="ai-cover-form-group">
            <label for="ai-cover-model">选择模型</label>
            <select id="ai-cover-model" class="ai-cover-select" v-model="model" :disabled="isLoading">
              <optgroup v-for="(group, platform) in groupedModels" :key="platform" :label="String(platform)">
                <option v-for="m in group" :key="m.id" :value="m.id">
                  {{ m.name }}
                </option>
              </optgroup>
              <option v-if="Object.keys(groupedModels).length === 0" value="" disabled>
                加载中...
              </option>
            </select>
          </div>
          <div class="ai-cover-form-group">
            <label for="ai-cover-size">图片尺寸</label>
            <!-- --- 核心改造：尺寸选择与自定义输入 --- -->
            <select id="ai-cover-size" class="ai-cover-select" v-model="sizeSelection" :disabled="isLoading">
              <option value="1024*1024">1024*1024</option>
              <option value="720*1280">720*1280 (竖屏)</option>
              <option value="1280*720">1280*720 (横屏)</option>
              <option value="custom">自定义...</option>
            </select>
            <input
              v-if="sizeSelection === 'custom'"
              type="text"
              class="ai-cover-input custom-size-input"
              v-model="customSize"
              placeholder="例如: 800*600"
              :disabled="isLoading"
            />
          </div>
        </div>
        
        <div class="ai-cover-form-group-checkbox">
          <input type="checkbox" id="ai-cover-upload-alist" v-model="uploadToAlist" :disabled="isLoading">
          <label for="ai-cover-upload-alist">将图片上传到 Alist (需在插件设置中配置)</label>
        </div>

        <div class="ai-cover-actions">
          <button 
            id="ai-cover-generate-btn" 
            class="ai-cover-button ai-cover-button-primary" 
            @click="generateImage"
            :disabled="isLoading"
          >
            <span v-if="isLoading" class="button-loader"></span>
            <span>{{ isLoading ? '运行中...' : '生成图片' }}</span>
          </button>
          <button id="ai-cover-close-btn" class="ai-cover-button ai-cover-button-secondary" @click="closeModal" :disabled="isLoading">
            关闭
          </button>
        </div>
        
        <div v-if="latestProgress" class="ai-cover-progress-container">
          <div :class="['progress-item', { 'is-error': latestProgress.isError, 'is-from-cache': latestProgress.isFromCache }]">
            <span class="progress-icon">{{ latestProgress.isError ? '❌' : (isLoading ? '⏳' : (latestProgress.isFromCache ? 'ℹ️' : '✅')) }}</span>
            <span class="progress-message">{{ latestProgress.message }}</span>
          </div>
        </div>

        <div v-if="previewUrl" id="ai-cover-preview-container" class="ai-cover-preview">
          <p>生成结果预览:</p>
          <img 
            id="ai-cover-preview-img" 
            :src="previewUrl" 
            alt="AI 生成图片预览"
            @click="isImageZoomed = true"
          >
          <div class="ai-cover-actions">
            <button
              id="ai-cover-copy-url-btn"
              class="ai-cover-button ai-cover-button-secondary"
              @click="copyUrl"
            >
              {{ copyButtonText }}
            </button>
            <button 
              id="ai-cover-use-image-btn" 
              class="ai-cover-button ai-cover-button-primary"
              @click="useImage"
            >
              使用这张图片
            </button>
          </div>
        </div>
      </div>
    </div>
  </Transition>

  <Transition name="modal-fade">
    <div v-if="isImageZoomed" class="image-zoom-overlay" @click="isImageZoomed = false">
      <img :src="previewUrl" class="zoomed-image" alt="放大的 AI 图片">
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { ref, watch, computed } from 'vue';

const props = defineProps<{ visible: boolean }>();
const emit = defineEmits(['close', 'use-image']);

const STORAGE_KEY = 'ai-cover-last-image-url';

const prompt = ref('');
const model = ref(''); 
const isLoading = ref(false);
const previewUrl = ref('');
const availableModels = ref<{name: string, id: string}[]>([]);
const isImageZoomed = ref(false);
const uploadToAlist = ref(true);
const latestProgress = ref<{ message: string, isError?: boolean, isFromCache?: boolean } | null>(null);
const copyButtonText = ref('复制链接');

// --- 核心改造 ---
const sizeSelection = ref('1024*1024'); // 用于下拉框
const customSize = ref(''); // 用于自定义输入框

// 计算最终发送给后端的尺寸值
const finalSize = computed(() => {
  return sizeSelection.value === 'custom' ? customSize.value : sizeSelection.value;
});


const groupedModels = computed(() => {
  const groups: { [key: string]: { name: string, id: string }[] } = {};
  for (const m of availableModels.value) {
    const [platform] = m.id.split(':', 2);
    if (!groups[platform]) groups[platform] = [];
    groups[platform].push({ name: m.name, id: m.id });
  }
  return groups;
});

const fetchModels = async () => {
  try {
    const response = await fetch('/api/plugins/aicover/models');
    if (!response.ok) throw new Error('获取模型列表失败');
    const modelsData = await response.json();
    availableModels.value = modelsData;
    if (modelsData.length > 0 && !model.value) {
      model.value = modelsData[0].id;
    }
  } catch (err: any) {
    console.error("加载 AI 模型列表失败:", err);
    latestProgress.value = { message: `加载模型列表失败: ${err.message}`, isError: true };
    availableModels.value = [{ name: '默认模型 (加载失败)', id: 'tongyi:wanx-v1' }];
    model.value = 'tongyi:wanx-v1';
  }
};

watch(() => props.visible, (isVisible) => {
  if (isVisible) {
    fetchModels();
    previewUrl.value = '';
    latestProgress.value = null;
    copyButtonText.value = '复制链接';

    const lastImageUrl = sessionStorage.getItem(STORAGE_KEY);
    if (lastImageUrl) {
      previewUrl.value = lastImageUrl;
      latestProgress.value = { 
        message: '已恢复上次生成的图片。', 
        isError: false,
        isFromCache: true
      };
    }
  }
});

const closeModal = () => {
  if (!isLoading.value) emit('close');
};

const useImage = () => {
  emit('use-image', previewUrl.value);
  sessionStorage.removeItem(STORAGE_KEY);
  closeModal();
};

const copyUrl = async () => {
  if (!previewUrl.value) return;
  try {
    await navigator.clipboard.writeText(previewUrl.value);
    copyButtonText.value = '已复制!';
    setTimeout(() => { copyButtonText.value = '复制链接'; }, 2000);
  } catch (err) {
    console.error('复制链接失败: ', err);
    copyButtonText.value = '复制失败';
     setTimeout(() => { copyButtonText.value = '复制链接'; }, 2000);
  }
};

const generateImage = () => {
  if (!prompt.value) {
    alert("请输入提示词！");
    return;
  }
  if (sizeSelection.value === 'custom' && !customSize.value) {
    alert("请输入自定义尺寸！");
    return;
  }

  isLoading.value = true;
  previewUrl.value = '';
  latestProgress.value = null;

  const params = new URLSearchParams({
    prompt: prompt.value,
    model: model.value,
    size: finalSize.value, // 使用计算后的尺寸
    uploadToAlist: String(uploadToAlist.value)
  });

  const eventSource = new EventSource(`/api/plugins/aicover/generate?${params.toString()}`);

  eventSource.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      latestProgress.value = { message: data.message, isError: data.isError };
      if (data.finalImageUrl) {
        previewUrl.value = data.finalImageUrl;
      }
      if (data.isFinal) {
        isLoading.value = false;
        eventSource.close();
        if (previewUrl.value && !data.isError) {
          sessionStorage.setItem(STORAGE_KEY, previewUrl.value);
        }
      }
    } catch (e) {
      latestProgress.value = { message: "收到无法解析的数据", isError: true };
    }
  };

  eventSource.onerror = () => {
    latestProgress.value = { message: "与服务器的连接中断。", isError: true };
    isLoading.value = false;
    eventSource.close();
  };
};
</script>

<style scoped>
/* --- 全面美化 --- */

/* 动画效果 */
.modal-fade-enter-active, .modal-fade-leave-active { transition: opacity 0.3s ease; }
.modal-fade-enter-from, .modal-fade-leave-to { opacity: 0; }
.modal-fade-enter-active .ai-cover-modal-content,
.modal-fade-leave-active .ai-cover-modal-content {
  transition: transform 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
}
.modal-fade-enter-from .ai-cover-modal-content,
.modal-fade-leave-to .ai-cover-modal-content {
  transform: scale(0.95) translateY(10px);
}

/* 遮罩层：玻璃拟态效果 */
.ai-cover-modal-overlay {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background-color: rgba(0, 0, 0, 0.5);
  display: flex; align-items: center; justify-content: center;
  z-index: 9999; 
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}

/* 弹窗主体：更柔和的阴影和边框 */
.ai-cover-modal-content {
  background-color: rgba(255, 255, 255, 0.95);
  color: #1f2937; 
  padding: 28px;
  border-radius: 16px;
  box-shadow: 0 10px 30px rgba(0,0,0,0.1);
  border: 1px solid rgba(255, 255, 255, 0.2);
  width: 90%; max-width: 550px; max-height: 90vh; overflow-y: auto;
}

/* 标题 */
.ai-cover-modal-title { 
  margin-top: 0; margin-bottom: 24px; font-size: 1.75rem; 
  text-align: center; font-weight: 700; color: #111827;
}

/* 表单组 */
.ai-cover-form-group { margin-bottom: 20px; }
.ai-cover-form-group-inline { display: flex; gap: 20px; }
.ai-cover-form-group label { 
  display: block; margin-bottom: 8px; font-weight: 500; 
  color: #4b5563; font-size: 0.875rem;
}

/* 输入框与下拉框：更现代的外观 */
.ai-cover-input, .ai-cover-select { 
  width: 100%; 
  padding: 12px 16px; 
  border: 1px solid #d1d5db; 
  border-radius: 8px; 
  font-size: 1rem; 
  box-sizing: border-box; 
  background-color: #ffffff;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.ai-cover-input:focus, .ai-cover-select:focus {
  outline: none;
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.3);
}

.ai-cover-select {
  -webkit-appearance: none; -moz-appearance: none; appearance: none;
  padding-right: 40px;
  background-image: url("data:image/svg+xml,%3csvg xmlns='http://www.w3.org/2000/svg' fill='none' viewBox='0 0 20 20'%3e%3cpath stroke='%236b7280' stroke-linecap='round' stroke-linejoin='round' stroke-width='1.5' d='M6 8l4 4 4-4'/%3e%3c/svg%3e");
  background-position: right 0.9rem center;
  background-repeat: no-repeat;
  background-size: 1.25em;
}

.custom-size-input {
  margin-top: 12px;
}

/* Alist 复选框 */
.ai-cover-form-group-checkbox { 
  display: flex; align-items: center; margin-bottom: 24px; 
}
.ai-cover-form-group-checkbox input { margin-right: 10px; width: 18px; height: 18px; }
.ai-cover-form-group-checkbox label { font-size: 0.9rem; color: #374151; }

/* 按钮区域 */
.ai-cover-actions { 
  margin-top: 28px; 
  display: flex; 
  justify-content: flex-end;
  gap: 12px;
}
.ai-cover-button { 
  padding: 12px 24px; 
  border: none; border-radius: 8px; 
  font-size: 1rem; font-weight: 600; 
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: all 0.2s ease;
}
.ai-cover-button:active { transform: scale(0.98); }

/* 主按钮：渐变效果 */
.ai-cover-button-primary { 
  background: linear-gradient(to right, #3b82f6, #60a5fa);
  color: white; 
}
.ai-cover-button-primary:hover {
  box-shadow: 0 4px 15px rgba(59, 130, 246, 0.3);
  transform: translateY(-2px);
}

/* 次要按钮 */
.ai-cover-button-secondary { 
  background-color: #e5e7eb; 
  color: #374151;
}
.ai-cover-button-secondary:hover {
  background-color: #d1d5db;
}
.ai-cover-button:disabled { 
  background-color: #9ca3af;
  color: #e5e7eb;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}

/* 进度/状态显示 */
.ai-cover-progress-container {
  margin-top: 20px; background-color: #f3f4f6; border-radius: 8px;
  padding: 12px 16px; border: 1px solid #e5e7eb;
}
.progress-item { display: flex; align-items: center; font-size: 0.9rem; color: #1f2937; }
.progress-item.is-error { color: #ef4444; }
.progress-item.is-from-cache { color: #3b82f6; }
.progress-icon { margin-right: 10px; font-size: 1.1rem; }

/* 图片预览 */
.ai-cover-preview { margin-top: 24px; text-align: center; }
.ai-cover-preview p { margin-bottom: 12px; font-weight: 500; }
.ai-cover-preview img { 
  cursor: zoom-in; 
  border-radius: 12px;
  box-shadow: 0 4px 15px rgba(0,0,0,0.05);
}

/* 图片放大 */
.image-zoom-overlay {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background-color: rgba(0, 0, 0, 0.85);
  display: flex; align-items: center; justify-content: center;
  z-index: 10000; cursor: zoom-out;
}
.zoomed-image { 
  max-width: 90vw; max-height: 90vh; 
  object-fit: contain; border-radius: 8px;
}

/* 加载中动画 */
.button-loader {
  width: 18px; height: 18px;
  border: 2px solid rgba(255, 255, 255, 0.5);
  border-left-color: #fff;
  border-radius: 50%;
  display: inline-block;
  animation: spin 1s linear infinite;
  margin-right: 8px;
}
@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

</style>

