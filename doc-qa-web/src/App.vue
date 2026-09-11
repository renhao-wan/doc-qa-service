<template>
  <a-config-provider :locale="locale">
    <router-view></router-view>
  </a-config-provider>
</template>

<script setup>
import { ref } from 'vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';

// 设置时间类组件为中文
dayjs.locale('zh-cn');

// 设置 Ant Design Vue 组件库默认语言为中文
const locale = ref(zhCN)
</script>

<style scoped>
</style>
