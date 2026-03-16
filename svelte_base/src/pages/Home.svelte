<script lang="ts">
  import { createEventDispatcher } from 'svelte'
  import Welcome from './home/Welcome.svelte'
  import Account from './home/Account.svelte'

  export let currentPath: string

  type SubRouteComponent = typeof Welcome

  let CurrentChild: SubRouteComponent | null = null
  const dispatch = createEventDispatcher<{ navigate: { path: string } }>()

  const isActive = (path: string) => (currentPath === path ? 'active' : '')

  const setChildFromPath = () => {
    if (currentPath === '/home/welcome') {
      CurrentChild = Welcome
    } else if (currentPath === '/home/account') {
      CurrentChild = Account
    } else {
      CurrentChild = null
    }
  }

  $: setChildFromPath()

  const navigate = (path: string) => {
    dispatch('navigate', { path })
  }
</script>

<div class="page">
  <h1>Home</h1>
  <p>Welcome to the Home page. Select a section below.</p>

  <div class="subnav">
    <a
      href="/home/welcome"
      class={isActive('/home/welcome')}
      on:click|preventDefault={() => navigate('/home/welcome')}
      >Welcome</a
    >
    <a
      href="/home/account"
      class={isActive('/home/account')}
      on:click|preventDefault={() => navigate('/home/account')}
      >Account</a
    >
  </div>

  {#if CurrentChild}
    <svelte:component this={CurrentChild} />
  {:else if currentPath === '/home'}
    <div>
      <p>Select a section from the navigation above to get started.</p>
    </div>
  {/if}
</div>

