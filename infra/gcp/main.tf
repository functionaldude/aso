data "google_project" "aso" {
  project_id = var.project_id
}

locals {
  labels     = { application = "aso", managed-by = "terraform" }
  public_url = var.public_url != "" ? var.public_url : "https://aso-${data.google_project.aso.number}.${var.region}.run.app"
  deploy_app = var.image != "" && var.oidc_issuer != "" && var.oidc_client_id != ""
  services = toset([
    "aiplatform.googleapis.com",
    "artifactregistry.googleapis.com",
    "cloudbuild.googleapis.com",
    "compute.googleapis.com",
    "iam.googleapis.com",
    "run.googleapis.com",
    "secretmanager.googleapis.com",
    "sqladmin.googleapis.com",
    "storage.googleapis.com",
  ])
}

resource "google_project_service" "aso" {
  for_each           = local.services
  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

resource "google_artifact_registry_repository" "aso" {
  project       = var.project_id
  location      = var.region
  repository_id = "aso"
  description   = "ASO application images"
  format        = "DOCKER"
  labels        = local.labels
  depends_on    = [google_project_service.aso]
}

resource "google_service_account" "runtime" {
  project      = var.project_id
  account_id   = "aso-runtime"
  display_name = "ASO Cloud Run runtime"
  depends_on   = [google_project_service.aso]
}

resource "google_project_iam_custom_role" "embedding" {
  project     = var.project_id
  role_id     = "asoEmbeddingPredictor"
  title       = "ASO embedding prediction"
  permissions = ["aiplatform.endpoints.predict"]
}

resource "google_project_iam_member" "embedding" {
  project = var.project_id
  role    = google_project_iam_custom_role.embedding.name
  member  = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_project_iam_member" "sql" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_service_account" "build" {
  project      = var.project_id
  account_id   = "aso-build"
  display_name = "ASO image builder"
  depends_on   = [google_project_service.aso]
}

resource "google_artifact_registry_repository_iam_member" "build" {
  project    = var.project_id
  location   = var.region
  repository = google_artifact_registry_repository.aso.name
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.build.email}"
}

resource "google_project_iam_member" "build_logs" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.build.email}"
}

resource "google_storage_bucket" "build" {
  project                     = var.project_id
  name                        = "${var.project_id}-aso-build"
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  labels                      = local.labels
  lifecycle_rule {
    condition { age = 7 }
    action { type = "Delete" }
  }
}

resource "google_storage_bucket_iam_member" "build" {
  bucket = google_storage_bucket.build.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.build.email}"
}

resource "random_password" "database" {
  length  = 32
  special = false
}

resource "google_sql_database_instance" "aso" {
  project             = var.project_id
  name                = "aso-db"
  region              = var.region
  database_version    = "POSTGRES_17"
  deletion_protection = true
  settings {
    tier                  = "db-f1-micro"
    edition               = "ENTERPRISE"
    availability_type     = "ZONAL"
    disk_type             = "PD_SSD"
    disk_size             = 10
    disk_autoresize       = true
    user_labels           = local.labels
    connector_enforcement = "REQUIRED"
    ip_configuration {
      ipv4_enabled = true
      ssl_mode     = "ENCRYPTED_ONLY"
      # No authorized networks: all application connections use the authenticated connector.
    }
    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      start_time                     = "02:00"
      backup_retention_settings { retained_backups = 7 }
    }
  }
  depends_on = [google_project_service.aso]
}

resource "google_sql_database" "aso" {
  project  = var.project_id
  name     = "aso"
  instance = google_sql_database_instance.aso.name
}

resource "google_sql_user" "aso" {
  project  = var.project_id
  name     = "aso"
  instance = google_sql_database_instance.aso.name
  password = random_password.database.result
}

resource "google_secret_manager_secret" "database" {
  project   = var.project_id
  secret_id = "aso-db-password"
  labels    = local.labels
  replication {
    auto {}
  }
  depends_on = [google_project_service.aso]
}

resource "google_secret_manager_secret_version" "database" {
  secret      = google_secret_manager_secret.database.id
  secret_data = random_password.database.result
}

resource "google_secret_manager_secret_iam_member" "database" {
  project   = var.project_id
  secret_id = google_secret_manager_secret.database.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_cloud_run_v2_service" "aso" {
  count               = local.deploy_app ? 1 : 0
  project             = var.project_id
  name                = "aso"
  location            = var.region
  deletion_protection = true
  ingress             = "INGRESS_TRAFFIC_ALL"
  labels              = local.labels
  template {
    service_account                  = google_service_account.runtime.email
    execution_environment            = "EXECUTION_ENVIRONMENT_GEN2"
    max_instance_request_concurrency = 40
    session_affinity                 = true
    timeout                          = "300s"
    scaling {
      min_instance_count = 1
      max_instance_count = 1
    }
    containers {
      image = var.image
      ports { container_port = 8080 }
      resources {
        limits            = { cpu = "1", memory = "1Gi" }
        cpu_idle          = false
        startup_cpu_boost = true
      }
      startup_probe {
        http_get { path = "/actuator/health" }
        initial_delay_seconds = 10
        period_seconds        = 10
        failure_threshold     = 24
        timeout_seconds       = 5
      }
      liveness_probe {
        http_get { path = "/actuator/health" }
        period_seconds    = 30
        failure_threshold = 3
        timeout_seconds   = 5
      }
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
  depends_on = [
    google_project_iam_member.sql,
    google_project_iam_member.embedding,
    google_secret_manager_secret_iam_member.database,
    google_sql_database.aso,
  ]
}

# OIDC protects private application data; the public shell and MCP discovery must be reachable.
resource "google_cloud_run_v2_service_iam_member" "public" {
  count    = local.deploy_app ? 1 : 0
  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.aso[0].name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

locals {
  runtime_environment = merge({
    ASO_ADDRESS                              = "0.0.0.0"
    ASO_PORT                                 = "8080"
    ASO_DB_URL                               = "jdbc:postgresql:///aso?cloudSqlInstance=${google_sql_database_instance.aso.connection_name}&socketFactory=com.google.cloud.sql.postgres.SocketFactory&cloudSqlRefreshStrategy=lazy"
    ASO_DB_USER                              = google_sql_user.aso.name
    SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE = "5"
    SPRING_DATASOURCE_HIKARI_MINIMUMIDLE     = "1"
    SERVER_FORWARDHEADERSSTRATEGY            = "framework"
    SERVER_SERVLET_SESSION_COOKIE_SECURE     = "true"
    ASO_PUBLIC_URL                           = local.public_url
    ASO_MCP_AUDIENCE                         = "${local.public_url}/mcp"
    ASO_OIDC_ISSUER                          = var.oidc_issuer
    ASO_OIDC_CLIENT_ID                       = var.oidc_client_id
    ASO_OIDC_GROUPS_CLAIM                    = "groups"
    ASO_OIDC_MEMBER_GROUP                    = var.oidc_member_group
    ASO_OIDC_ADMIN_GROUP                     = var.oidc_admin_group
    ASO_EMBEDDING_ENABLED                    = "true"
    ASO_EMBEDDING_PROVIDER                   = "vertex"
    ASO_EMBEDDING_VERTEX_PROJECT             = var.project_id
    ASO_EMBEDDING_VERTEX_LOCATION            = var.region
    ASO_EMBEDDING_MODEL                      = "gemini-embedding-001"
    ASO_EMBEDDING_DIMENSIONS                 = "768"
    ASO_EMBEDDING_PREPARATION_VERSION        = "vertex-retrieval-v1"
    ASO_EMBEDDING_QUERY_PREFIX               = ""
    ASO_EMBEDDING_DOCUMENT_PREFIX            = ""
    ASO_EMBEDDING_BATCH_SIZE                 = "1"
    ASO_EMBEDDING_CONNECT_TIMEOUT            = "2s"
    ASO_EMBEDDING_REQUEST_TIMEOUT            = "5s"
    ASO_EMBEDDING_INDEXING_TIMEOUT           = "30s"
  }, var.runtime_environment)
}
