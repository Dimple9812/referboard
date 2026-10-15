import { useState } from 'react';
import { Check, Copy } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from './ui/Button';

export function CopyButton({ text, onCopied, label = 'Copy' }: { text: string; onCopied?: () => void; label?: string }) {
  const [done, setDone] = useState(false);
  return (
    <Button variant="outline" size="sm" onClick={async () => {
      try {
        await navigator.clipboard.writeText(text);
        setDone(true);
        onCopied?.();
        setTimeout(() => setDone(false), 1800);
      } catch {
        toast.error('Copy failed. Select the text and copy it manually.');
      }
    }}>
      {done ? <Check className="h-4 w-4 text-fit" aria-hidden /> : <Copy className="h-4 w-4" aria-hidden />}
      {done ? 'Copied' : label}
    </Button>
  );
}
