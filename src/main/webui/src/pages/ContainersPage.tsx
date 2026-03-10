import { useState, useEffect, useCallback } from 'react'
import { Link } from 'react-router-dom'
import type { DockerContainer } from '../types'
import {
  getContainers,
  stopContainer,
  startContainer,
  removeContainer,
  getAllowedRepositories,
} from '../services/containerService'
import NewContainerModal from '../components/NewContainerModal'

export default function ContainersPage() {
  const [containers, setContainers] = useState<DockerContainer[]>([])
  const [filter, setFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [hasRepos, setHasRepos] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)

  const machineIp = window.location.hostname

  const loadContainers = useCallback(() => {
    setLoading(true)
    getContainers()
      .then(setContainers)
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    loadContainers()
    getAllowedRepositories().then((repos) => setHasRepos(repos.length > 0)).catch(() => {})
  }, [loadContainers])

  async function handleStop(id: string) {
    if (!confirm(`Stop ${id} ?`)) return
    await stopContainer(id)
    loadContainers()
  }

  async function handleStart(id: string) {
    if (!confirm(`Start ${id} ?`)) return
    await startContainer(id)
    loadContainers()
  }

  async function handleRemove(id: string) {
    if (!confirm(`Remove ${id} ?`)) return
    await removeContainer(id)
    loadContainers()
  }

  const isUp = (status: string) => status.includes('Up')

  const filtered = containers.filter((c) =>
    Object.values(c).some((v) => v.toLowerCase().includes(filter.toLowerCase()))
  )

  return (
    <>
      <div className="jumbotron text-center">
        <h1>Docker Handler</h1>
        <p>A simple docker web handler!</p>
        <Link to="/images" className="btn btn-custom btn-lg mt-4">Explore Images</Link>
      </div>

      <div className="container mt-5">
        <div className="d-flex justify-content-between align-items-center mb-4">
          <h2 className="mb-0">Containers</h2>
          {hasRepos && (
            <button className="btn btn-success" onClick={() => setModalOpen(true)}>
              <i className="fa fa-plus-circle"></i> New Container
            </button>
          )}
        </div>

        <div className="input-group mb-4">
          <span className="input-group-text"><i className="fa fa-search text-muted"></i></span>
          <input
            className="form-control"
            type="text"
            placeholder="Search containers..."
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
        </div>

        <div className="table-responsive">
          <table className="table table-hover table-bordered align-middle">
            <thead className="table-dark">
              <tr>
                <th>Container ID</th>
                <th>Image</th>
                <th>Command</th>
                <th>Created</th>
                <th>Status</th>
                <th>Ports</th>
                <th>Name</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr><td colSpan={8} className="text-center">Loading...</td></tr>
              )}
              {!loading && filtered.length === 0 && (
                <tr><td colSpan={8} className="text-center text-muted">No containers found</td></tr>
              )}
              {filtered.map((c) => (
                <tr key={c.containerId}>
                  <td>{c.containerId}</td>
                  <td>{c.image}</td>
                  <td>{c.command}</td>
                  <td>{c.created}</td>
                  <td>{c.status}</td>
                  <td>
                    <b>
                      {c.ports !== '-'
                        ? c.ports.split(',').map((port, i) => (
                            <span key={i}>
                              {i > 0 && ', '}
                              <a href={`http://${machineIp}:${port.trim()}`} target="_blank" rel="noreferrer">
                                {port.trim()}
                              </a>
                            </span>
                          ))
                        : '-'}
                    </b>
                  </td>
                  <td><b>{c.names}</b></td>
                  <td>
                    {isUp(c.status) && (
                      <button className="btn btn-warning btn-sm m-1" onClick={() => handleStop(c.containerId)}>
                        stop
                      </button>
                    )}
                    {!isUp(c.status) && (
                      <button className="btn btn-primary btn-sm m-1" onClick={() => handleStart(c.containerId)}>
                        start
                      </button>
                    )}
                    <button className="btn btn-danger btn-sm m-1" onClick={() => handleRemove(c.containerId)}>
                      remove
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <NewContainerModal
        visible={modalOpen}
        onClose={() => setModalOpen(false)}
        onCreated={loadContainers}
      />
    </>
  )
}
