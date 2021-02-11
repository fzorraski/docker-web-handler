import {removeDockerImage} from "../services/DockerImageService.js";
import {showLoader} from "../views/DockerImagesView.js";
import {responseWrapper} from "../utils/PromiseHelpers.js";


export function actionRemoveDockerImage(id) {
    if (confirm('remove imageId ' + id + ' ?')) {
            showLoader();
            removeDockerImage(id).then(res => {
                responseWrapper(res);
        });
    }
}