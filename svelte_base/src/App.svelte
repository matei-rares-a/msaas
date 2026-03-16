<script lang="ts">
  import { onMount } from 'svelte'
  import Home from './pages/Home.svelte'
  import Admin from './pages/Admin.svelte'
  import NotFound from './pages/NotFound.svelte'

  let currentPath = '/'
  let CurrentComponent: any = Home

  const navigate = (path: string) => {
    if (path === currentPath) return
    window.history.pushState({}, '', path)
    setPathFromLocation()
  }

  const setPathFromLocation = () => {
    currentPath = window.location.pathname || '/'

    if (currentPath.startsWith('/admin')) {
      CurrentComponent = Admin
    } else if (currentPath === '/' || currentPath.startsWith('/home')) {
      CurrentComponent = Home
    } else {
      CurrentComponent = NotFound
    }
  }

  const isActive = (path: string) =>
    currentPath.startsWith(path) ? 'active' : ''

  onMount(() => {
    setPathFromLocation()
    const onPopState = () => setPathFromLocation()
    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
  })
</script>

<nav>
  <a
    href="/home"
    class={isActive('/home')}
    on:click|preventDefault={() => navigate('/home')}
    >Home</a
  >
  <a
    href="/admin"
    class={isActive('/admin')}
    on:click|preventDefault={() => navigate('/admin')}
    >Admin</a
  >
</nav>

<div class="container">
  <svelte:component
    this={CurrentComponent}
    {currentPath}
    on:navigate={(event: CustomEvent<{ path: string }>) =>
      navigate(event.detail.path)
    }
  />
</div>

