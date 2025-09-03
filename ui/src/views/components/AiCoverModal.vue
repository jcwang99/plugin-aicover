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
              <option v-for="m in availableModels" :key="m.id" :value="m.id">
                {{ m.name }}
              </option>
              <option v-if="!availableModels.length" value="" disabled>
                加载中...
              </option>
            </select>
          </div>
          <div class="ai-cover-form-group">
            <label for="ai-cover-size">图片尺寸</label>
            <select id="ai-cover-size" class="ai-cover-select" v-model="size" :disabled="isLoading">
              <option value="1024*1024">1024*1024</option>
              <option value="720*1280">720*1280 (竖屏)</option>
              <option value="1280*720">1280*720 (横屏)</option>
            </select>
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
            {{ isLoading ? '运行中...' : '生成图片' }}
          </button>
          <button id="ai-cover-close-btn" class="ai-cover-button ai-cover-button-secondary" @click="closeModal" :disabled="isLoading">
            关闭
          </button>
        </div>
        
        <!-- --- 核心改造：实时进度显示区域 --- -->
        <div v-if="latestProgress" class="ai-cover-progress-container">
          <div :class="['progress-item', { 'is-error': latestProgress.isError }]">
            <span class="progress-icon">{{ latestProgress.isError ? '❌' : (isLoading ? '⏳' : '✅') }}</span>
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
import { ref, watch } from 'vue';

// 定义组件接收的属性 (props) 和发出的事件 (emits)
const props = defineProps<{ visible: boolean }>();
const emit = defineEmits(['close', 'use-image']);

// 组件内部状态
const prompt = ref('');
const model = ref(''); 
const size = ref('1024*1024');
const isLoading = ref(false);
const previewUrl = ref('');
const availableModels = ref<{name: string, id: string}[]>([]);
const isImageZoomed = ref(false);
const uploadToAlist = ref(true);
const latestProgress = ref<{ message: string, isError?: boolean } | null>(null);

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
    availableModels.value = [{ name: '默认模型 (加载失败)', id: 'wanx-v1' }];
    model.value = 'wanx-v1';
  }
};

watch(() => props.visible, (isVisible) => {
  if (isVisible) {
    fetchModels();
    previewUrl.value = '';
    latestProgress.value = null;
  }
});

const closeModal = () => {
  if (!isLoading.value) emit('close');
};

const useImage = () => {
  emit('use-image', previewUrl.value);
  closeModal();
};

const generateImage = () => {
  if (!prompt.value) {
    alert("请输入提示词！");
    return;
  }

  isLoading.value = true;
  previewUrl.value = '';
  latestProgress.value = null;

  const params = new URLSearchParams({
    prompt: prompt.value,
    model: model.value,
    size: size.value,
    uploadToAlist: String(uploadToAlist.value)
  });

  const eventSource = new EventSource(`/api/plugins/aicover/generate?${params.toString()}`);

  eventSource.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      
      latestProgress.value = {
        message: data.message,
        isError: data.isError
      };

      if (data.finalImageUrl) {
        previewUrl.value = data.finalImageUrl;
      }
      
      if (data.isFinal) {
        isLoading.value = false;
        eventSource.close();
      }
    } catch (e) {
      console.error("解析 SSE 数据失败:", e);
      latestProgress.value = { message: "收到无法解析的数据", isError: true };
    }
  };

  eventSource.onerror = (error) => {
    console.error("EventSource 发生错误:", error);
    latestProgress.value = { message: "与服务器的连接中断，请检查网络或后台日志。", isError: true };
    isLoading.value = false;
    eventSource.close();
  };
};
</script>

<style scoped>
.modal-fade-enter-active, .modal-fade-leave-active { transition: opacity 0.3s ease; }
.modal-fade-enter-from, .modal-fade-leave-to { opacity: 0; }
.ai-cover-modal-overlay {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background-color: rgba(0, 0, 0, 0.6);
  display: flex; align-items: center; justify-content: center;
  z-index: 9999; backdrop-filter: blur(5px);
}
.ai-cover-modal-content {
  background-color: white; color: #333; padding: 24px;
  border-radius: 12px; box-shadow: 0 5px 20px rgba(0,0,0,0.25);
  width: 90%; max-width: 550px; max-height: 90vh; overflow-y: auto;
  display: flex; flex-direction: column;
}
.ai-cover-modal-title {
  margin-top: 0;
  margin-bottom: 24px;
  font-size: 1.5rem;
  color: #1a1a1a;
  text-align: center;
  flex-shrink: 0;
}
.ai-cover-form-group {
  margin-bottom: 16px;
  flex: 1;
  flex-shrink: 0;
}
.ai-cover-form-group-inline {
  display: flex;
  gap: 16px;
  flex-shrink: 0;
}
.ai-cover-form-group label {
  display: block;
  margin-bottom: 8px;
  font-weight: 500;
  color: #555;
}
.ai-cover-input, .ai-cover-select {
  width: 100%;
  padding: 12px;
  border: 1px solid #ccc;
  border-radius: 6px;
  font-size: 1rem;
  box-sizing: border-box;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.ai-cover-input:focus, .ai-cover-select:focus {
  outline: none;
  border-color: #007bff;
  box-shadow: 0 0 0 2px rgba(0, 123, 255, 0.25);
}
.ai-cover-form-group-checkbox { display: flex; align-items: center; margin-bottom: 20px; }
.ai-cover-form-group-checkbox input { margin-right: 8px; width: 16px; height: 16px; }
.ai-cover-form-group-checkbox label { font-size: 0.9rem; color: #555; }
.ai-cover-actions {
  margin-top: 24px;
  display: flex;
  justify-content: flex-end;
  flex-shrink: 0;
}
.ai-cover-button {
  padding: 10px 20px;
  border: none;
  border-radius: 6px;
  font-size: 1rem;
  font-weight: 500;
  cursor: pointer;
  transition: background-color 0.2s, transform 0.1s;
}
.ai-cover-button:active {
    transform: scale(0.98);
}
.ai-cover-button-primary {
  background-color: #007bff;
  color: white;
}
.ai-cover-button-primary:hover {
  background-color: #0056b3;
}
.ai-cover-button-secondary {
  background-color: #f0f0f0;
  color: #333;
  margin-left: 12px;
}
.ai-cover-button-secondary:hover {
  background-color: #e0e0e0;
}
.ai-cover-button:disabled {
  background-color: #ccc;
  cursor: not-allowed;
}
.ai-cover-progress-container {
  margin-top: 16px; background-color: #f7f7f7; border-radius: 8px;
  padding: 10px 12px; border: 1px solid #eee;
}
.progress-item { display: flex; align-items: center; font-size: 0.9rem; color: #333; }
.progress-item.is-error { color: #d9534f; }
.progress-icon { margin-right: 8px; flex-shrink: 0; }
.ai-cover-preview {
  margin-top: 24px;
  text-align: center;
  flex-shrink: 0;
}
.ai-cover-preview img {
  max-width: 100%;
  max-height: 50vh;
  object-fit: contain;
  border-radius: 8px;
  border: 1px solid #ddd;
  margin-top: 8px;
  display: inline-block;
  cursor: zoom-in;
}
.image-zoom-overlay {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background-color: rgba(0, 0, 0, 0.8);
  display: flex; align-items: center; justify-content: center;
  z-index: 10000; cursor: zoom-out;
}
.zoomed-image { max-width: 90vw; max-height: 90vh; object-fit: contain; }
</style>

