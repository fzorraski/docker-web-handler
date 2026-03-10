import { useState, useEffect } from 'react'
import {
  getAllowedRepositories,
  getRepositoryTags,
  runContainer,
} from '../services/containerService'

interface Props {
  visible: boolean
  onClose: () => void
  onCreated: () => void
}

interface EnvVar {
  key: string
  value: string
}

export default function NewContainerModal({ visible, onClose, onCreated }: Props) {
  const [repositories, setRepositories] = useState<string[]>([])
  const [selectedRepo, setSelectedRepo] = useState('')
  const [allTags, setAllTags] = useState<string[]>([])
  const [tagFilter, setTagFilter] = useState('')
  const [selectedTag, setSelectedTag] = useState('')
  const [tagsLoading, setTagsLoading] = useState(false)
  const [containerName, setContainerName] = useState('')
  const [envVars, setEnvVars] = useState<EnvVar[]>([])
  const [running, setRunning] = useState(false)

  useEffect(() => {
    getAllowedRepositories().then(setRepositories).catch(() => {})
  }, [])

  useEffect(() => {
    if (!selectedRepo) {
      setAllTags([])
      setSelectedTag('')
      return
    }
    setTagsLoading(true)
    setSelectedTag('')
    setTagFilter('')
    getRepositoryTags(selectedRepo).then((res) => {
      if (res.state === 1 && res.tags) {
        setAllTags(res.tags)
      } else {
        setAllTags([])
        if (res.message) alert('Error: ' + res.message)
      }
    }).catch(() => setAllTags([]))
      .finally(() => setTagsLoading(false))
  }, [selectedRepo])

  const filteredTags = tagFilter
    ? allTags.filter((t) => t.toLowerCase().includes(tagFilter.toLowerCase()))
    : allTags

  function addEnvVar() {
    setEnvVars([...envVars, { key: '', value: '' }])
  }

  function updateEnvVar(index: number, field: 'key' | 'value', val: string) {
    const updated = [...envVars]
    updated[index][field] = val
    setEnvVars(updated)
  }

  function removeEnvVar(index: number) {
    setEnvVars(envVars.filter((_, i) => i !== index))
  }

  function resetForm() {
    setSelectedRepo('')
    setSelectedTag('')
    setTagFilter('')
    setAllTags([])
    setContainerName('')
    setEnvVars([])
  }

  async function handleRun() {
    if (!selectedRepo) return alert('Please select a repository.')
    if (!selectedTag) return alert('Please select a tag.')

    const name = containerName ? ` as "${containerName}"` : ''
    if (!confirm(`Run container from ${selectedRepo}:${selectedTag}${name} ?`)) return

    setRunning(true)
    try {
      const envList = envVars
        .filter((e) => e.key.trim())
        .map((e) => `${e.key.trim()}=${e.value.trim()}`)

      const res = await runContainer(selectedRepo, selectedTag, containerName, envList)
      if (res.state === 1) {
        alert(res.message)
        resetForm()
        onClose()
        onCreated()
      } else {
        alert('Error: ' + res.message)
      }
    } catch (err) {
      alert('Error: ' + err)
    } finally {
      setRunning(false)
    }
  }

  function handleClose() {
    resetForm()
    onClose()
  }

  if (!visible) return null

  return (
    <div className="modal-backdrop-custom" onClick={handleClose}>
      <div className="modal-dialog modal-lg" onClick={(e) => e.stopPropagation()}>
        <div className="modal-content run-container-card">
          <div className="modal-header card-header">
            <h5 className="modal-title">
              <i className="fa fa-plus-circle"></i> New Container
            </h5>
            <button type="button" className="close text-white" onClick={handleClose}>
              <span>&times;</span>
            </button>
          </div>
          <div className="modal-body">
            {/* Repository + Tag */}
            <div className="row mb-3">
              <div className="col-md-4">
                <label><b>Repository</b></label>
                <select
                  className="form-control"
                  value={selectedRepo}
                  onChange={(e) => setSelectedRepo(e.target.value)}
                >
                  <option value="">Select a repository...</option>
                  {repositories.map((r) => (
                    <option key={r} value={r}>{r}</option>
                  ))}
                </select>
              </div>
              <div className="col-md-4">
                <label><b>Filter tags</b></label>
                <input
                  type="text"
                  className="form-control"
                  placeholder="Type to filter..."
                  value={tagFilter}
                  onChange={(e) => setTagFilter(e.target.value)}
                />
              </div>
              <div className="col-md-4">
                <label><b>Tag</b></label>
                <select
                  className="form-control"
                  value={selectedTag}
                  onChange={(e) => setSelectedTag(e.target.value)}
                  disabled={!selectedRepo || tagsLoading}
                >
                  <option value="">
                    {tagsLoading ? 'Loading tags...' : !selectedRepo ? 'Select a repository first...' : 'Select a tag...'}
                  </option>
                  {filteredTags.map((t) => (
                    <option key={t} value={t}>{t}</option>
                  ))}
                </select>
              </div>
            </div>
            {/* Container name */}
            <div className="row mb-3">
              <div className="col-md-6">
                <label><b>Container Name</b></label>
                <input
                  type="text"
                  className="form-control"
                  placeholder="e.g. my-app-container (optional)"
                  value={containerName}
                  onChange={(e) => setContainerName(e.target.value)}
                />
              </div>
            </div>
            {/* Environment Variables */}
            <div className="row mb-3">
              <div className="col-md-12">
                <label><b>Environment Variables</b></label>
                {envVars.map((env, i) => (
                  <div className="env-var-row" key={i}>
                    <input
                      type="text"
                      className="form-control env-key"
                      placeholder="KEY"
                      value={env.key}
                      onChange={(e) => updateEnvVar(i, 'key', e.target.value)}
                    />
                    <span>=</span>
                    <input
                      type="text"
                      className="form-control env-value"
                      placeholder="VALUE"
                      value={env.value}
                      onChange={(e) => updateEnvVar(i, 'value', e.target.value)}
                    />
                    <button
                      type="button"
                      className="btn-remove-env"
                      title="Remove"
                      onClick={() => removeEnvVar(i)}
                    >
                      <i className="fa fa-times"></i>
                    </button>
                  </div>
                ))}
                <button
                  type="button"
                  className="btn btn-outline-secondary btn-sm mt-2"
                  onClick={addEnvVar}
                >
                  <i className="fa fa-plus"></i> Add Variable
                </button>
              </div>
            </div>
          </div>
          <div className="modal-footer">
            <button type="button" className="btn btn-secondary" onClick={handleClose}>Cancel</button>
            <button
              type="button"
              className="btn btn-success"
              onClick={handleRun}
              disabled={running}
            >
              <i className={`fa ${running ? 'fa-spinner fa-spin' : 'fa-play'}`}></i>
              {running ? ' Running...' : ' Run Container'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
