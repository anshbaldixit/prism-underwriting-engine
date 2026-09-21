import { Navigate, NavLink, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import LoginPage from './pages/LoginPage'
import ApplyPage from './pages/ApplyPage'
import DecisionPage from './pages/DecisionPage'
import QueuePage from './pages/QueuePage'
import ModelPage from './pages/ModelPage'

export default function App() {
  const { session, logout } = useAuth()
  if (!session) return <LoginPage />
  const staff = session.role === 'UNDERWRITER' || session.role === 'ADMIN'
  return (
    <div className="app">
      <header className="topbar">
        <div className="brand"><span className="logo" />Prism Underwriting</div>
        <nav>
          <NavLink to="/apply">Apply</NavLink>
          <NavLink to="/applications">{staff ? 'Queue' : 'My applications'}</NavLink>
          {staff && <NavLink to="/model">Model & fairness</NavLink>}
        </nav>
        <span className="spacer" />
        <span className="user">{session.displayName} · {session.role.toLowerCase()}</span>
        <button className="btn sm" onClick={logout}>Sign out</button>
      </header>
      <main className="main">
        <Routes>
          <Route path="/" element={<Navigate to={staff ? '/applications' : '/apply'} replace />} />
          <Route path="/apply" element={<ApplyPage />} />
          <Route path="/applications" element={<QueuePage />} />
          <Route path="/applications/:id" element={<DecisionPage />} />
          {staff && <Route path="/model" element={<ModelPage />} />}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  )
}
