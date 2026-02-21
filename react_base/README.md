# MSaaS Frontend

A modern React TypeScript frontend application with routing for the MSaaS platform.

## Setup

### Prerequisites
- Node.js 16+ 
- npm or yarn

### Installation

```bash
# Install dependencies
npm install
```

### Development

```bash
# Start development server (runs on port 3000)
npm run dev
```

### Build

```bash
# Build for production
npm run build
```

### Preview

```bash
# Preview production build
npm run preview
```

## Project Structure

```
src/
├── main.tsx              # Application entry point
├── App.tsx               # Main app component with routing
├── index.css             # Global styles
└── pages/
    ├── Home.tsx          # Home page with sub-routes
    ├── Home/
    │   ├── Welcome.tsx   # Welcome sub-page
    │   └── Account.tsx   # Account sub-page
    ├── Admin.tsx         # Admin dashboard
    └── NotFound.tsx      # 404 page
```

## Routes

### Main Routes
- `/` - Redirects to `/home`
- `/home` - Home page (default landing page)
- `/admin` - Admin dashboard

### Sub-routes (under /home)
- `/home/welcome` - Welcome section
- `/home/account` - Account management section

### Catch-all
- `*` - 404 Not Found page

## Technologies

- React 18.2
- TypeScript 5.3
- React Router DOM 6.20
- Vite 5.0
- CSS3

## Features

- ✅ TypeScript support for type safety
- ✅ Client-side routing with React Router
- ✅ Responsive design with CSS
- ✅ Nested routing for sub-pages
- ✅ Active route highlighting
- ✅ 404 error handling
