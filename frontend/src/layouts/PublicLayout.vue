<template>
  <div class="public-layout">
    <!-- Left decorative panel -->
    <div class="public-layout__left" aria-hidden="true">
      <div class="pub-deco">
        <div class="pub-deco__title">BetterCloudDrive</div>
        <div class="pub-deco__sub">安全 · 高效 · 可靠</div>
        <div class="pub-deco__grid">
          <div v-for="n in 16" :key="n" class="pub-deco__cell" :style="{ animationDelay: `${n * 120}ms` }" />
        </div>
      </div>
    </div>
    <!-- Right form panel -->
    <div class="public-layout__right">
      <RouterView />
    </div>
  </div>
</template>

<style scoped>
.public-layout {
  min-height: calc(100vh - var(--desktop-titlebar-h, 0px)); display: flex;
}
.public-layout__left {
  flex: 1;
  background: var(--bg-surface);
  border-right: 1px solid var(--border);
  display: flex; align-items: center; justify-content: center;
  overflow: hidden; position: relative;
}
.public-layout__right {
  width: 440px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  padding: 40px 20px;
  background: var(--bg-base);
}
@media (max-width: 768px) {
  .public-layout__left { display: none; }
  .public-layout__right { width: 100%; }
}
.pub-deco { text-align: center; z-index: 1; }
.pub-deco__title {
  font-size: 36px; font-weight: 700; letter-spacing: -0.04em;
  color: var(--text-primary); margin-bottom: 8px;
}
.pub-deco__sub { font-size: 14px; color: var(--text-secondary); margin-bottom: 48px; letter-spacing: 0.1em; }
.pub-deco__grid {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px;
  width: 200px; margin: 0 auto;
}
.pub-deco__cell {
  aspect-ratio: 1;
  background: var(--bg-elevated); border: 1px solid var(--border); border-radius: 8px;
  animation: cell-pulse 3s ease-in-out infinite;
}
@keyframes cell-pulse {
  0%, 100% { opacity: 0.2; }
  50% { opacity: 0.7; background: var(--accent-dim); border-color: rgba(0,212,170,0.2); }
}
</style>
