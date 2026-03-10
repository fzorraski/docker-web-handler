import { Link, useLocation } from 'react-router-dom'

export default function Navbar() {
  const location = useLocation()

  return (
    <nav className="navbar navbar-expand-sm">
      <Link className="navbar-brand" to="/">Docker Handler</Link>
      <button className="navbar-toggler" type="button" data-toggle="collapse" data-target="#collapsibleNavbar">
        <span className="navbar-toggler-icon"></span>
      </button>
      <div className="collapse navbar-collapse" id="collapsibleNavbar">
        <ul className="navbar-nav ml-auto">
          <li className={`nav-item ${location.pathname === '/' ? 'active' : ''}`}>
            <Link className="nav-link" to="/">Containers</Link>
          </li>
          <li className={`nav-item ${location.pathname === '/images' ? 'active' : ''}`}>
            <Link className="nav-link" to="/images">Images</Link>
          </li>
        </ul>
      </div>
    </nav>
  )
}
