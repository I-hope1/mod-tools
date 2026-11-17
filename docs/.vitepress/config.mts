import { defineConfig } from 'vitepress'

export default defineConfig({
  lang: 'zh-CN',
  title: 'Mod Tools',
  description: '面向 Mindustry v7 的开发者工具箱：JS 编辑器、UI 审查、内容浏览、任务执行器与热重载。',
  base: '/mod-tools/',
  lastUpdated: true,
  cleanUrls: false,
  head: [
    ['link', { rel: 'icon', href: '/mod-tools/assets/icon.png' }],
    ['meta', { name: 'theme-color', content: '#646cff' }],
  ],
  sitemap: {
    hostname: 'https://i-hope1.github.io/mod-tools/',
  },
  themeConfig: {
    logo: '/assets/icon.png',
    siteTitle: 'Mod Tools',
    nav: [
      { text: '指南', link: '/guide/', activeMatch: '/guide/' },
      { text: '热重载', link: '/guide/hotswap' },
      { text: '开发', link: '/guide/development' },
      { text: 'Remold', link: 'https://github.com/I-hope1/Remold' },
      { text: 'English', link: '/index_en' },
    ],
    sidebar: {
      '/guide/': [
        {
          text: '开始',
          items: [{ text: '快速开始', link: '/guide/' }],
        },
        {
          text: '工具',
          items: [
            { text: 'Tester', link: '/guide/tester' },
            { text: 'Executor', link: '/guide/executor' },
            { text: 'Selection', link: '/guide/selection' },
            { text: 'UnitSpawn', link: '/guide/unit-spawn' },
            { text: 'ReviewElement', link: '/guide/review-element' },
            { text: 'ShowUIList / ShowInfoWindow', link: '/guide/ui-inspector' },
            { text: '窗口与 Frag', link: '/guide/windows' },
            { text: '其他工具', link: '/guide/misc' },
            { text: '扩展与实验性设置', link: '/guide/extensions' },
          ],
        },
        {
          text: '进阶',
          items: [
            { text: '热重载 / Remold', link: '/guide/hotswap' },
            { text: '开发与构建', link: '/guide/development' },
          ],
        },
      ],
    },
    search: {
      provider: 'local',
      options: {
        translations: {
          button: { buttonText: '搜索文档', buttonAriaLabel: '搜索文档' },
          modal: {
            noResultsText: '没有找到结果',
            resetButtonTitle: '清除查询',
            footer: {
              selectText: '选择',
              navigateText: '切换',
              closeText: '关闭',
            },
          },
        },
      },
    },
    outline: {
      level: [2, 3],
      label: '本页目录',
    },
    editLink: {
      pattern: 'https://github.com/I-hope1/mod-tools/edit/main/docs/:path',
      text: '在 GitHub 上编辑此页',
    },
    lastUpdated: {
      text: '最后更新',
      formatOptions: { dateStyle: 'short', timeStyle: 'short' },
    },
    docFooter: {
      prev: '上一页',
      next: '下一页',
    },
    footer: {
      message: '面向 Mindustry v7 的开发者工具箱',
      copyright: 'AGPL-3.0 · I-hope1',
    },
    socialLinks: [{ icon: 'github', link: 'https://github.com/I-hope1/mod-tools' }],
    sidebarMenuLabel: '菜单',
    returnToTopLabel: '回到顶部',
    darkModeSwitchLabel: '主题',
    lightModeSwitchTitle: '切换到浅色模式',
    darkModeSwitchTitle: '切换到深色模式',
    externalLinkIcon: true,
  },
})
