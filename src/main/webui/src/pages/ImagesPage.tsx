import { useState, useEffect, useCallback } from 'react'
import { Link } from 'react-router-dom'
import type { DockerImage } from '../types'
import { getImages, removeImage } from '../services/imageService'

const ERROR_IMAGE_IN_USE = 100
const ERROR_IMAGE_WITH_CHILD = 101

export default function ImagesPage() {
  const [images, setImages] = useState<DockerImage[]>([])
  const [filter, setFilter] = useState('')
  const [loading, setLoading] = useState(true)

  const loadImages = useCallback(() => {
    setLoading(true)
    getImages()
      .then(setImages)
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    loadImages()
  }, [loadImages])

  async function handleRemove(imageId: string) {
    if (!confirm(`Remove image ${imageId} ?`)) return
    try {
      const res = await removeImage(imageId)
      if (res.state === ERROR_IMAGE_IN_USE) {
        alert('** Image in use **\n' + res.message)
      } else if (res.state === ERROR_IMAGE_WITH_CHILD) {
        alert('** Image has dependent child images **\n' + res.message)
      }
      loadImages()
    } catch (err) {
      alert('Error: ' + err)
    }
  }

  const filtered = images.filter((img) =>
    Object.values(img).some((v) => v.toLowerCase().includes(filter.toLowerCase()))
  )

  return (
    <>
      <div className="jumbotron text-center">
        <h1>Docker Handler</h1>
        <p>A simple docker web handler!</p>
        <Link to="/" className="btn btn-custom btn-lg mt-4">Explore Containers</Link>
      </div>

      <div className="container mt-5">
        <div className="d-flex justify-content-between align-items-center mb-4">
          <h2 className="mb-0">Images</h2>
        </div>

        <div className="input-group mb-4">
          <span className="input-group-text"><i className="fa fa-search text-muted"></i></span>
          <input
            className="form-control"
            type="text"
            placeholder="Search images..."
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
        </div>

        <div className="table-responsive">
          <table className="table table-hover table-bordered align-middle">
            <thead className="table-dark">
              <tr>
                <th>Repository</th>
                <th>Tag</th>
                <th>Image ID</th>
                <th>Created</th>
                <th>Size</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr><td colSpan={6} className="text-center">Loading...</td></tr>
              )}
              {!loading && filtered.length === 0 && (
                <tr><td colSpan={6} className="text-center text-muted">No images found</td></tr>
              )}
              {filtered.map((img) => (
                <tr key={img.imageId}>
                  <td><b>{img.repository}</b></td>
                  <td>{img.tag}</td>
                  <td>{img.imageId}</td>
                  <td>{img.created}</td>
                  <td>{img.size}</td>
                  <td>
                    <button className="btn btn-danger btn-sm" onClick={() => handleRemove(img.imageId)}>
                      remove
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}
