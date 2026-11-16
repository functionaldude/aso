output "public_url" { value = local.public_url }
output "service_url" { value = try(google_cloud_run_v2_service.aso[0].uri, null) }
output "image_repository" { value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.aso.repository_id}/aso" }
output "database_connection" { value = google_sql_database_instance.aso.connection_name }
output "runtime_service_account" { value = google_service_account.runtime.email }
output "browser_callback" { value = "${local.public_url}/login/oauth2/code/oidc" }
output "logout_callback" { value = "${local.public_url}/?signedOut" }
