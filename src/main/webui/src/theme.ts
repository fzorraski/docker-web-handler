import { createTheme } from '@mui/material/styles'

const theme = createTheme({
  palette: {
    primary: {
      main: '#1e3d59',
    },
    secondary: {
      main: '#ff6f61',
    },
    background: {
      default: '#f4f6f9',
    },
  },
  typography: {
    fontFamily: "'Poppins', 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif",
  },
})

export default theme
