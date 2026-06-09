<template>
  <div class="public-layout">
    <!-- Background glow -->
    <div class="public-bg">
      <div class="glow-orb top-right"></div>
      <div class="glow-orb bottom-left"></div>
    </div>

    <!-- Desktop: side-by-side layout -->
    <div class="public-container">
      <!-- Hero / branding panel -->
      <div class="public-hero">
        <div class="hero-content">
          <div class="hero-icon">
            <svg width="48" height="48" viewBox="0 0 48 48" fill="none">
              <rect width="48" height="48" rx="14" fill="var(--accent)" opacity="0.12"/>
              <path d="M14 19l10-6 10 6v10l-10 6-10-6V19z" stroke="var(--accent)" stroke-width="2" fill="none"/>
              <circle cx="24" cy="24" r="4" fill="var(--accent)"/>
            </svg>
          </div>
          <h1 class="hero-title">BetterCloudDrive</h1>
          <p class="hero-sub">安全、快速、自托管的云存储</p>
          <div class="hero-features">
            <div class="hero-feat">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--accent)" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
              <span>分片上传 + 秒传</span>
            </div>
            <div class="hero-feat">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--accent)" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
              <span>文件分享与协作</span>
            </div>
            <div class="hero-feat">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--accent)" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
              <span>10GB 免费存储</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Form panel -->
      <div class="public-form-panel">
        <div class="public-card">
          <!-- Mobile-only logo -->
          <div class="public-logo mobile-only">
            <svg width="28" height="28" viewBox="0 0 48 48" fill="none">
              <rect width="48" height="48" rx="14" fill="var(--accent)" opacity="0.12"/>
              <path d="M14 19l10-6 10 6v10l-10 6-10-6V19z" stroke="var(--accent)" stroke-width="2" fill="none"/>
              <circle cx="24" cy="24" r="4" fill="var(--accent)"/>
            </svg>
            <span class="brand-name">BetterCloudDrive</span>
          </div>
          <router-view />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.public-layout {
  min-height: 100vh; display: flex; align-items: center; justify-content: center;
  padding: 24px; position: relative; overflow: hidden;
  background: var(--bg-deep);
}
.public-bg { position: fixed; inset: 0; pointer-events: none; z-index: 0; }
.glow-orb {
  position: absolute; width: 600px; height: 600px; border-radius: 50%;
  filter: blur(150px); opacity: 0.08;
}
.glow-orb.top-right { top: -200px; right: -200px; background: var(--accent); }
.glow-orb.bottom-left { bottom: -200px; left: -200px; background: var(--blue); }

/* Two-panel container */
.public-container {
  position: relative; z-index: 1;
  display: flex; width: 100%; max-width: 960px;
  background: var(--bg-surface); border-radius: var(--radius-xl);
  border: 1px solid var(--border-subtle);
  box-shadow: 0 16px 64px rgba(0,0,0,0.5);
  overflow: hidden;
  animation: scaleIn var(--duration-slow) var(--ease-out);
  min-height: 540px;
}

/* Hero panel — desktop left side */
.public-hero {
  flex: 1; display: flex; align-items: center; justify-content: center;
  padding: 48px 40px;
  background: linear-gradient(135deg, var(--bg-elevated) 0%, var(--bg-deep) 100%);
  border-right: 1px solid var(--border-subtle);
}
.hero-content { max-width: 320px; }
.hero-icon { margin-bottom: 20px; }
.hero-title {
  font-family: var(--font-display); font-size: 28px; font-weight: 700;
  letter-spacing: -0.03em; margin-bottom: 8px;
}
.hero-sub {
  color: var(--text-secondary); font-size: 15px; margin-bottom: 32px; line-height: 1.6;
}
.hero-features { display: flex; flex-direction: column; gap: 14px; }
.hero-feat {
  display: flex; align-items: center; gap: 10px; font-size: 14px;
  color: var(--text-secondary);
}

/* Form panel */
.public-form-panel {
  flex: 1; display: flex; align-items: center; justify-content: center;
  padding: 40px;
}
.public-card {
  width: 100%; max-width: 380px;
  animation: fadeIn 0.3s var(--ease-out) 0.1s both;
}
.public-logo {
  display: flex; align-items: center; gap: 10px; justify-content: center;
  margin-bottom: 28px;
}
.brand-name {
  font-family: var(--font-display); font-size: 18px; font-weight: 700;
  color: var(--text-primary); letter-spacing: -0.02em;
}
.mobile-only { display: none; }

/* Tablet & Mobile adjustments */
@media (max-width: 800px) {
  .public-hero { display: none; }
  .public-container { max-width: 440px; min-height: auto; }
  .public-form-panel { padding: 36px 28px; }
  .mobile-only { display: flex; }
}
@media (max-width: 480px) {
  .public-layout { padding: 12px; }
  .public-container { border-radius: var(--radius-lg); }
  .public-form-panel { padding: 28px 20px; }
  .public-card { max-width: 100%; }
}
</style>
