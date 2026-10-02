import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { AdminService, InAppNotification } from '../../core/services/admin.service';

@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './admin.component.html'
})
export class AdminComponent implements OnInit {
  notifications: InAppNotification[] = [];
  errorMessage: string | null = null;

  constructor(private adminService: AdminService) {}

  ngOnInit(): void {
    this.loadNotifications();
  }

  markAsRead(id: string): void {
    this.adminService.markAsRead(id).subscribe({
      next: () => this.loadNotifications(),
      error: () => {
        this.errorMessage = 'Failed to update notification state';
      }
    });
  }

  private loadNotifications(): void {
    this.adminService.getInAppNotifications().subscribe({
      next: (notifications) => {
        this.notifications = notifications;
      },
      error: () => {
        this.errorMessage = 'Failed to load in-app notifications';
      }
    });
  }
}
