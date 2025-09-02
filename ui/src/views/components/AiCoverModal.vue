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
          />
        </div>

        <div class="ai-cover-form-group-inline">
          <div class="ai-cover-form-group">
            <label for="ai-cover-model">选择模型</label>
            <select id="ai-cover-model" class="ai-cover-select" v-model="model">
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
            <select id="ai-cover-size" class="ai-cover-select" v-model="size">
              <option value="1024*1024">1024*1024</option>
              <option value="720*1280">720*1280 (竖屏)</option>
              <option value="1280*720">1280*720 (横屏)</option>
            </select>
          </div>
        </div>

        <!-- --- 新增 Alist 上传选项 --- -->
        <div class="ai-cover-form-group-checkbox">
          <input type="checkbox" id="ai-cover-upload-alist" v-model="uploadToAlist">
          <label for="ai-cover-upload-alist">将图片上传到 Alist (需在插件设置中配置)</label>
        </div>

        <div class="ai-cover-actions">
          <button 
            id="ai-cover-generate-btn" 
            class="ai-cover-button ai-cover-button-primary" 
            @click="generateImage"
            :disabled="isLoading"
          >
            {{ isLoading ? '生成中...' : '生成图片' }}
          </button>
          <button id="ai-cover-close-btn" class="ai-cover-button ai-cover-button-secondary" @click="closeModal">
            关闭
          </button>
        </div>
        
        <div v-if="isLoading" class="ai-cover-loading">
          <p>图片正在生成，请稍候...</p>
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
         <p v-if="error" class="ai-cover-error-text">{{ error }}</p>
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
import { ref, onMounted } from 'vue';

const props = defineProps<{
  visible: boolean;
}>();

const emit = defineEmits(['close', 'use-image']);

const prompt = ref('');
const model = ref(''); 
const size = ref('1024*1024');
const isLoading = ref(false);
const previewUrl = ref('');
const error = ref('');
const availableModels = ref<{name: string, id: string}[]>([]);
const isImageZoomed = ref(false);
const uploadToAlist = ref(true); // 新增状态，控制是否上传到 Alist

onMounted(async () => {
  try {
    const response = await fetch('/api/plugins/aicover/models');
    if (!response.ok) {
      throw new Error('获取模型列表失败');
    }
    const modelsData = await response.json();
    availableModels.value = modelsData;
    if (modelsData.length > 0) {
      model.value = modelsData[0].id;
    }
  } catch (err) {
    console.error("加载 AI 模型列表失败:", err);
    error.value = "加载模型列表失败，请检查插件设置。";
    availableModels.value = [{ name: '默认模型 (加载失败)', id: 'wanx-v1' }];
    model.value = 'wanx-v1';
  }
});

const closeModal = () => {
  if (!isLoading.value) {
    emit('close');
  }
};

const useImage = () => {
  emit('use-image', previewUrl.value);
  closeModal();
};

const generateImage = async () => {
  if (!prompt.value) {
    alert("请输入提示词！");
    return;
  }

  isLoading.value = true;
  previewUrl.value = '';
  error.value = '';

  try {
    const response = await fetch("/api/plugins/aicover/generate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ 
        prompt: prompt.value, 
        model: model.value, 
        size: size.value,
        uploadToAlist: uploadToAlist.value // 将 Alist 上传选项发送给后端
      }),
    });
    
    if (!response.ok) {
      const errorData = await response.json().catch(() => ({ message: `HTTP 错误! 状态码: ${response.status}` }));
      throw new Error(errorData.message || `HTTP 错误! 状态码: ${response.status}`);
    }
    
    const result = await response.json();
    const imageUrl = result.imageUrl;

    if (!imageUrl) {
        throw new Error("未能从 API 响应中获取图片 URL。响应内容: " + JSON.stringify(result));
    }

    previewUrl.value = imageUrl;

  } catch (e: any) {
    console.error("AI Cover: 图片生成失败", e);
    error.value = `图片生成失败: ${e.message}`;
  } finally {
    isLoading.value = false;
  }
};
</script>

<style scoped>
.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.3s ease;
}
.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}
.modal-fade-enter-active .ai-cover-modal-content,
.modal-fade-leave-active .ai-cover-modal-content {
  transition: transform 0.3s ease;
}
.modal-fade-enter-from .ai-cover-modal-content,
.modal-fade-leave-to .ai-cover-modal-content {
  transform: scale(0.95);
}

.ai-cover-modal-overlay {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background-color: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  backdrop-filter: blur(5px);
}
.ai-cover-modal-content {
  background-color: white;
  color: #333;
  padding: 24px;
  border-radius: 12px;
  box-shadow: 0 5px 20px rgba(0,0,0,0.25);
  width: 90%;
  max-width: 550px;
  max-height: 90vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
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
/* --- 新增复选框样式 --- */
.ai-cover-form-group-checkbox {
  display: flex;
  align-items: center;
  margin-bottom: 20px;
}
.ai-cover-form-group-checkbox input {
  margin-right: 8px;
  width: 16px;
  height: 16px;
}
.ai-cover-form-group-checkbox label {
  font-size: 0.9rem;
  color: #555;
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
.ai-cover-loading, .ai-cover-error-text {
  margin-top: 24px;
  text-align: center;
  color: #555;
  flex-shrink: 0;
}
.ai-cover-error-text {
  color: #d9534f;
  font-weight: 500;
}

.image-zoom-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.8);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10000;
  cursor: zoom-out;
}
.zoomed-image {
  max-width: 90vw;
  max-height: 90vh;
  object-fit: contain;
  box-shadow: 0 0 30px rgba(255, 255, 255, 0.2);
  border-radius: 4px;
}
</style>

