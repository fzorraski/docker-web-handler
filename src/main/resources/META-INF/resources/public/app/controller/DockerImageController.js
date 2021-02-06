import {removeDockerImage} from "../services/docker-image-service.js";
import {showLoader} from "../views/DockerImagesView.js";
import {responseWrapper} from "../utils/promise-helpers.js";


export function actionRemoveDockerImage(id) {
    if (confirm('remove imageId ' + id + ' ?')) {
            showLoader();
            removeDockerImage(id).then(res => {
                responseWrapper(res);
        });
    }
}