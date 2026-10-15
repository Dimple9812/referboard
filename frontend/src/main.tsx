import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Toaster, toast } from 'sonner';
import axios from 'axios';
import { errorMessage } from '@/api/client';
import { AuthProvider } from '@/auth/AuthProvider';
import App from './App';
import './index.css';

/** Phones have the tab bar and primary buttons at the bottom, so toasts go to the top there. */
const narrow = typeof window !== 'undefined' && window.matchMedia?.('(max-width: 767px)').matches;

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      // Don't retry what won't change (4xx); retry network blips and 5xx once.
      retry: (count, err) => !(axios.isAxiosError(err) && err.response && err.response.status < 500) && count < 1,
    },
  },
  // Failed mutations show a toast unless the screen handles the error itself (meta.silent).
  mutationCache: new MutationCache({
    onError: (err, _v, _c, mutation) => {
      if (!mutation.meta?.silent) toast.error(errorMessage(err));
    },
  }),
  queryCache: new QueryCache(),
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <App />
          <Toaster position={narrow ? 'top-center' : 'bottom-right'} toastOptions={{ className: 'font-sans' }} />
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
