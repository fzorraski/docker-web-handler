import { Component, type ReactNode } from 'react'
import { Box, Typography, Button } from '@mui/material'

interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
}

export default class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  render() {
    if (this.state.hasError) {
      return (
        <Box sx={{ textAlign: 'center', py: 10 }}>
          <Typography variant="h5" sx={{ mb: 2 }}>
            Something went wrong.
          </Typography>
          <Button
            variant="contained"
            onClick={() => {
              this.setState({ hasError: false })
              window.location.reload()
            }}
          >
            Reload
          </Button>
        </Box>
      )
    }
    return this.props.children
  }
}
