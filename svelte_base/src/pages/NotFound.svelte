<script lang="ts">
  const navigateHome = () => {
    window.history.pushState({}, '', '/home')
    window.dispatchEvent(new PopStateEvent('popstate'))
  }
</script>

<div class="page">
  <h1>404 - Page Not Found</h1>
  <p>The page you're looking for doesn't exist.</p>
  <a href="/home" on:click|preventDefault={navigateHome}>Go back to Home</a>
</div>

