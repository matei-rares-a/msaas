import { Link, useLocation, Outlet } from 'react-router-dom'

export default function Home() {
  const location = useLocation()

  const isActive = (path: string) => {
    return location.pathname === path ? 'active' : ''
  }

  return (
    <div className="page">
      <h1>Home</h1>
      <p>Welcome to the Home page. Select a section below.</p>

      <div className="subnav">
        <Link to="/home/welcome" className={isActive('/home/welcome')}>
          Welcome
        </Link>
        <Link to="/home/account" className={isActive('/home/account')}>
          Account
        </Link>
      </div>

      <Outlet />

      {location.pathname === '/home' && (
        <div>
          <p>Select a section from the navigation above to get started.</p>
        </div>
      )}
    </div>
  )
}
