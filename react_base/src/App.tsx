import { BrowserRouter as Router, Routes, Route, Link, useLocation } from 'react-router-dom'
import Home from './pages/Home'
import Welcome from './pages/Home/Welcome'
import Account from './pages/Home/Account'
import Admin from './pages/Admin'
import NotFound from './pages/NotFound'

function AppContent() {
  const location = useLocation()

  const isActive = (path: string) => {
    return location.pathname.startsWith(path) ? 'active' : ''
  }

  return (
    <>
      <nav>
        <Link to="/home" className={isActive('/home')}>
          Home
        </Link>
        <Link to="/admin" className={isActive('/admin')}>
          Admin
        </Link>
      </nav>

      <div className="container">
        <Routes>
          <Route path="/home" element={<Home />}>
            <Route path="welcome" element={<Welcome />} />
            <Route path="account" element={<Account />} />
          </Route>
          <Route path="/admin" element={<Admin />} />
          <Route path="/" element={<Home />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </div>
    </>
  )
}

function App() {
  return (
    <Router>
      <AppContent />
    </Router>
  )
}

export default App
