import { Compass } from 'lucide-react';
import { Link } from 'react-router';
import { EmptyState } from '@/components/shared';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/primitives';
import { useDocumentTitle } from '@/hooks';

export default function NotFoundPage() {
  useDocumentTitle('Not found');
  return (
    <Card>
      <EmptyState
        icon={Compass}
        title="Page not found"
        description="The page you're looking for doesn't exist or you don't have access to it."
        action={
          <Button asChild>
            <Link to="/">Back to overview</Link>
          </Button>
        }
      />
    </Card>
  );
}
