import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NotificationService, NotificationView } from '../../core/services/notification.service';

/** The notifications of the active tenant, newest first, with what is unread marked (REQ-NOTIF-04). */
@Component({
  selector: 'app-notifications',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './notifications.component.html',
  styleUrls: ['./notifications.component.css']
})
export class NotificationsComponent implements OnInit {
  items$ = this.notifications.items$;
  unread$ = this.notifications.unread$;
  loading = true;
  error: string | null = null;

  constructor(private notifications: NotificationService) {}

  ngOnInit() {
    this.notifications.reload().subscribe({
      next: () => (this.loading = false),
      error: () => {
        this.error = 'The notifications could not be loaded.';
        this.loading = false;
      }
    });
  }

  open(item: NotificationView) {
    if (!item.read) {
      this.notifications.markRead(item.id).subscribe({ error: () => (this.error = 'That could not be marked as read.') });
    }
  }

  markAllRead() {
    this.notifications.markAllRead().subscribe({ error: () => (this.error = 'They could not be marked as read.') });
  }

  trackById(_: number, item: NotificationView) {
    return item.id;
  }
}
