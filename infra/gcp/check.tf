# Run explicitly before configuring OIDC; uses the same image, identity, secrets and settings as the service.
resource "google_cloud_run_v2_job" "check" {
  count               = var.image != "" ? 1 : 0
  project             = var.project_id
  name                = "aso-deployment-check"
  location            = var.region
  deletion_protection = false
  labels              = local.labels
  template {
    template {
      service_account = google_service_account.runtime.email
      max_retries     = 0
      timeout         = "300s"
      containers {
        image   = var.image
        command = ["java"]
        args = [
          "-Dloader.main=com.functionaldude.aso.deployment.DeploymentCheck",
          "-cp", "/app/aso.jar",
          "org.springframework.boot.loader.launch.PropertiesLauncher",
        ]
        resources { limits = { cpu = "1", memory = "1Gi" } }
        dynamic "env" {
          for_each = local.runtime_environment
          content {
            name  = env.key
            value = env.value
          }
        }
        env {
          name = "ASO_DB_PASSWORD"
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.database.secret_id
              version = google_secret_manager_secret_version.database.version
            }
          }
        }
      }
    }
  }
  depends_on = [
    google_project_service.aso,
    google_project_iam_member.sql,
    google_project_iam_member.embedding,
    google_secret_manager_secret_iam_member.database,
    google_sql_database.aso,
  ]
}
